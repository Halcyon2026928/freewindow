package io.github.halcyon2026928.freewindow;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 智能侧边栏（com.coloros.smartsidebar）：解除编辑模式"最多添加 20 个应用/工具"的限制。
 *
 * <p>从作者自己的 sidebar-hook 模块移植（MIT）。四处 hook 分别管：
 * <ul>
 *   <li>{@code UserPanelView.canAdd}：编辑动画期间之外一律允许添加</li>
 *   <li>{@code 边栏数据读取 n.u / n.t}：读取时用空列表骗过 {@code size() < 20} 的截断</li>
 *   <li>{@code updateEditLabelAndScroll2LastUserData}：插入时不因占位项提前返回</li>
 *   <li>{@code performAdd}：添加后滚动到新增项</li>
 * </ul>
 */
public class SidebarHook implements IXposedHookLoadPackage {

    private static final String TAG = "[freewindow-sidebar]";
    private static final String TARGET_PACKAGE = "com.coloros.smartsidebar";
    private static final String USER_PANEL_VIEW =
            "com.oplus.smartsidebar.panelview.edgepanel.mainpanel.UserPanelView";
    private static final String EDGE_PANEL_FILE_UTIL =
            "com.oplus.smartsidebar.utils.n";
    private static final String APP_LABEL_DATA =
            "com.oplus.smartsidebar.panelview.edgepanel.data.AppLabelData";
    private static final String VIEW_TYPE =
            "com.oplus.smartsidebar.panelview.data.ViewType";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + " loaded: " + lpparam.packageName);
        hookCanAdd(lpparam.classLoader);
        hookReadUserBeans(lpparam.classLoader);
        hookUpdateEditLabel(lpparam.classLoader);
        hookPerformAdd(lpparam.classLoader);
    }

    private void hookCanAdd(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(USER_PANEL_VIEW, classLoader, "canAdd", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean animating = (boolean) XposedHelpers.callMethod(
                                    param.thisObject, "isEditAnimating");
                            param.setResult(!animating);
                        }
                    });
            XposedBridge.log(TAG + " canAdd hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook canAdd failed: " + t);
        }
    }

    /**
     * 旧版（GT8 / ColorOS 13）：{@code n.u(XmlPullParser, List)}；
     * 新版（ColorOS 17）：{@code n.t(XmlPullParser, List)}，内部有 {@code list.size() < 20} 限制。
     * 两个签名都尝试，存在哪个用哪个。
     */
    private void hookReadUserBeans(ClassLoader classLoader) {
        hookReadMethod(classLoader, "u");
        hookReadMethod(classLoader, "t");
    }

    private void hookReadMethod(ClassLoader classLoader, final String methodName) {
        try {
            XposedHelpers.findAndHookMethod(EDGE_PANEL_FILE_UTIL, classLoader, methodName,
                    XmlPullParser.class, List.class,
                    new XC_MethodHook() {
                        private List<?> originalList;
                        private ArrayList<Object> fakeList;

                        @Override
                        @SuppressWarnings("unchecked")
                        protected void beforeHookedMethod(MethodHookParam param) {
                            originalList = (List<?>) param.args[1];
                            fakeList = new ArrayList<Object>() {
                                @Override
                                public int size() {
                                    return 0;
                                }
                            };
                            param.args[1] = fakeList;
                        }

                        @Override
                        @SuppressWarnings("unchecked")
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (fakeList != null && originalList != null) {
                                ((List<Object>) originalList).addAll(fakeList);
                            }
                            originalList = null;
                            fakeList = null;
                        }
                    });
            XposedBridge.log(TAG + " readUserBeans hooked: n." + methodName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook n." + methodName + " not available: " + t);
        }
    }

    private void hookUpdateEditLabel(ClassLoader classLoader) {
        try {
            Class<?> appLabelDataClass = XposedHelpers.findClass(APP_LABEL_DATA, classLoader);
            Class<?> viewTypeClass = XposedHelpers.findClass(VIEW_TYPE, classLoader);
            final Object editOccupancy =
                    XposedHelpers.getStaticObjectField(viewTypeClass, "EDIT_OCCUPANCY");

            XposedHelpers.findAndHookMethod(USER_PANEL_VIEW, classLoader,
                    "updateEditLabelAndScroll2LastUserData",
                    int.class, appLabelDataClass, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int i10 = (int) param.args[0];
                            Object appLabelData = param.args[1];

                            List<Object> panelData = (List<Object>) XposedHelpers.getObjectField(
                                    param.thisObject, "mPanelData");
                            if (panelData.indexOf(appLabelData) >= 0) return;

                            boolean hasOccupancy = false;
                            if (i10 >= 0 && i10 < panelData.size()) {
                                Object item = panelData.get(i10);
                                Object viewType = XposedHelpers.callMethod(item, "getViewType");
                                if (viewType == editOccupancy) hasOccupancy = true;
                            }
                            if (hasOccupancy) return;

                            panelData.add(appLabelData);
                            int insertPos = panelData.size() - 1;

                            Object mUserListPanel = XposedHelpers.getObjectField(
                                    param.thisObject, "mUserListPanel");
                            Object mUserList = XposedHelpers.callMethod(mUserListPanel, "getMUserList");
                            Object adapter = XposedHelpers.callMethod(mUserList, "getAdapter");
                            if (adapter != null) {
                                XposedHelpers.callMethod(adapter, "notifyItemInserted", insertPos);
                            }
                            XposedHelpers.callMethod(mUserList, "scrollToPosition", insertPos);

                            param.setResult(null);
                        }
                    });
            XposedBridge.log(TAG + " updateEditLabel hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook updateEditLabel failed: " + t);
        }
    }

    private void hookPerformAdd(ClassLoader classLoader) {
        try {
            Class<?> appLabelDataClass = XposedHelpers.findClass(APP_LABEL_DATA, classLoader);
            Class<?> viewTypeClass = XposedHelpers.findClass(VIEW_TYPE, classLoader);
            final Object editOccupancy =
                    XposedHelpers.getStaticObjectField(viewTypeClass, "EDIT_OCCUPANCY");

            XposedHelpers.findAndHookMethod(USER_PANEL_VIEW, classLoader, "performAdd",
                    appLabelDataClass,
                    new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void afterHookedMethod(MethodHookParam param) {
                            List<Object> panelData = (List<Object>) XposedHelpers.getObjectField(
                                    param.thisObject, "mPanelData");

                            for (Object item : panelData) {
                                Object viewType = XposedHelpers.callMethod(item, "getViewType");
                                if (viewType == editOccupancy) return;
                            }

                            Object mUserListPanel = XposedHelpers.getObjectField(
                                    param.thisObject, "mUserListPanel");
                            Object mUserList = XposedHelpers.callMethod(mUserListPanel, "getMUserList");
                            XposedHelpers.callMethod(mUserList, "scrollToPosition", panelData.size());
                        }
                    });
            XposedBridge.log(TAG + " performAdd hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook performAdd failed: " + t);
        }
    }
}
