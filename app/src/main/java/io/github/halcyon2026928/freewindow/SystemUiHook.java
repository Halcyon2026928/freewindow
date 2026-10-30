package io.github.halcyon2026928.freewindow;

import android.app.Application;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SystemUI 侧入口。
 *
 * <p>只做一件事：拿到 SystemUI 的 Application 上下文，把 {@link SystemUiDock} 装起来。
 * 优先挂 {@code com.android.systemui.SystemUIApplication#onCreate}，失败则退回
 * {@code android.app.Application#onCreate}（OEM 改名/换壳时也能装上）。
 */
public class SystemUiHook implements IXposedHookLoadPackage {

    private static final String TAG = "[freewindow-systemui]";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!DockProtocol.SYSTEMUI_PKG.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + " loading in " + lpparam.packageName);
        if (hookSystemUiApplication(lpparam)) return;
        hookApplicationFallback();
    }

    private boolean hookSystemUiApplication(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> type = XposedHelpers.findClass(
                    "com.android.systemui.SystemUIApplication", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(type, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        SystemUiDock.install((Application) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " install error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked SystemUIApplication.onCreate");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " SystemUIApplication hook failed: " + t);
            return false;
        }
    }

    private void hookApplicationFallback() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object app = param.thisObject;
                        if (!(app instanceof Application)) return;
                        Application application = (Application) app;
                        if (!DockProtocol.SYSTEMUI_PKG.equals(application.getPackageName())) return;
                        SystemUiDock.install(application);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " fallback install error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked Application.onCreate fallback");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Application fallback hook failed: " + t);
        }
    }
}
