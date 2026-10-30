package io.github.halcyon2026928.freewindow;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 选择"动态应用"：勾选的应用在坞窗里走实时镜像，其余走静态截图。
 *
 * <p>性能：应用列表在**后台线程**枚举（图标也懒加载，只在本行真正显示时才取），
 * 所以进页面立即有 UI，不再等几十个应用图标加载完。
 */
public class DynamicAppsActivity extends Activity {

    /** 推荐（适合实时画面）的应用，仅用于打标签提示，不影响勾选结果。 */
    private static final String[] RECOMMENDED = {
            "com.ss.android.ugc.aweme",
            "tv.danmaku.bili",
            "com.smile.gifmaker",
            "com.tencent.qqlive",
            "com.qiyi.video",
            "com.youku.phone",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.google.android.youtube",
            "com.mxtech.videoplayer.pro",
            "org.videolan.vlc",
    };

    private static final class Entry {
        final String pkg;
        final String label;
        final boolean recommended;
        Drawable icon;

        Entry(String pkg, String label, boolean recommended) {
            this.pkg = pkg;
            this.label = label;
            this.recommended = recommended;
        }
    }

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    private ListView list;
    private AppAdapter adapter;
    private String keyword = "";

    // 主题相关（跟随设置里的浅色/深色）
    private boolean light;
    private int colorBackground;
    private int colorSearch;
    private int colorCard;
    private int colorName;
    private int colorPackage;
    private int colorBadgeBg;
    private int colorBadgeText;
    private int colorCheckOff;
    private int colorHint;
    private Drawable rowBackground;
    private Drawable searchBackground;
    private Drawable badgeBackground;
    private Drawable checkOffBackground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean lightTheme = getSharedPreferences("ui_prefs", MODE_PRIVATE)
                .getBoolean("light_theme", true);
        light = lightTheme;
        setTheme(lightTheme ? R.style.LightTheme : R.style.AppTheme);
        setTitle("动态应用（实时预览）");
        initThemeColors();

        selected.addAll(readSelection());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colorBackground);
        int pad = dp(14);
        root.setPadding(0, pad, 0, 0);

        // 搜索框：圆角胶囊 + 放大镜
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(searchBackground);
        searchBox.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        searchParams.leftMargin = pad;
        searchParams.rightMargin = pad;
        root.addView(searchBox, searchParams);

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(R.drawable.ic_picker_search);
        searchBox.addView(searchIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        EditText search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(colorName);
        search.setHintTextColor(colorHint);
        search.setBackground(null);
        search.setPadding(dp(12), 0, 0, 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                keyword = s == null ? "" : s.toString();
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        searchBox.addView(search, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setPadding(pad, dp(12), pad, pad);
        list.setClipToPadding(false);
        list.setVisibility(View.INVISIBLE);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String pkg = shown.get(position).pkg;
                if (!selected.remove(pkg)) selected.add(pkg);
                saveSelection();
                // 勾上的立刻置顶。
                applyFilter();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 加载中：转圈 + 文字
        LinearLayout loadingBox = new LinearLayout(this);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        loadingBox.setGravity(Gravity.CENTER);
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        spinner.setIndeterminate(true);
        loadingBox.addView(spinner, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView loadingText = new TextView(this);
        loadingText.setText("正在读取应用…");
        loadingText.setTextSize(13);
        loadingText.setTextColor(colorHint);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(10);
        loadingBox.addView(loadingText, textParams);
        root.addView(loadingBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // 枚举应用放到后台线程，进页面立即可见。
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Entry> loaded = loadApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(loaded);
                        loadingBox.setVisibility(View.GONE);
                        list.setVisibility(View.VISIBLE);
                        applyFilter();
                    }
                });
            }
        }, "freewindow-apps").start();
    }

    /** 浅色 / 深色两套配色，跟着设置里的主题走。 */
    private void initThemeColors() {
        if (light) {
            colorBackground = 0xFFF5F5F5;
            colorSearch = 0xFFF0F0F0;
            colorCard = 0xFFFFFFFF;
            colorName = 0xFF111111;
            colorPackage = 0xFF8A8A8A;
            colorBadgeBg = 0xFFEAF1FF;
            colorBadgeText = 0xFF3D6DFF;
            colorCheckOff = 0xFFE2E2E2;
            colorHint = 0xFF9A9A9A;
        } else {
            colorBackground = 0xFF0F0F12;
            colorSearch = 0xFF23232A;
            colorCard = 0xFF1C1C22;
            colorName = 0xFFF2F2F2;
            colorPackage = 0xFF9A9AA2;
            colorBadgeBg = 0xFF1E2A44;
            colorBadgeText = 0xFF7FA6FF;
            colorCheckOff = 0xFF3A3A42;
            colorHint = 0xFF7A7A82;
        }
        rowBackground = rounded(colorCard, 18);
        searchBackground = rounded(colorSearch, 26);
        badgeBackground = rounded(colorBadgeBg, 8);
        checkOffBackground = rounded(colorCheckOff, 100);
    }

    private Drawable rounded(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private List<Entry> loadApps() {
        List<Entry> entries = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<>();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
                String pkg = info.activityInfo.packageName;
                if (!seen.add(pkg)) continue;
                CharSequence label = info.loadLabel(pm);
                entries.add(new Entry(pkg, label == null ? pkg : label.toString(), isRecommended(pkg)));
            }
        } catch (Throwable ignored) {
        }
        // 兜底：某些 ROM / 分身用户下 queryIntentActivities 会被裁剪。
        try {
            for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                if (!seen.add(app.packageName)) continue;
                if (pm.getLaunchIntentForPackage(app.packageName) == null) continue;
                CharSequence label = pm.getApplicationLabel(app);
                entries.add(new Entry(app.packageName,
                        label == null ? app.packageName : label.toString(),
                        isRecommended(app.packageName)));
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return entries;
    }

    private boolean isRecommended(String pkg) {
        for (String item : RECOMMENDED) {
            if (pkg.equals(item)) return true;
        }
        return false;
    }

    private void applyFilter() {
        String key = keyword == null ? "" : keyword.trim().toLowerCase();
        shown.clear();
        // 已勾选的置顶，其余按名称。
        for (int pass = 0; pass < 2; pass++) {
            boolean wantSelected = pass == 0;
            for (Entry entry : all) {
                if (selected.contains(entry.pkg) != wantSelected) continue;
                if (!key.isEmpty()
                        && !entry.label.toLowerCase().contains(key)
                        && !entry.pkg.toLowerCase().contains(key)) continue;
                shown.add(entry);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private Set<String> readSelection() {
        Set<String> out = new LinkedHashSet<>();
        String csv = ModulePrefs.get(this).getString(ModulePrefs.KEY_DYNAMIC_APPS, "");
        if (csv == null || csv.isEmpty()) return out;
        for (String item : csv.split(",")) {
            if (!item.isEmpty()) out.add(item);
        }
        return out;
    }

    private void saveSelection() {
        StringBuilder sb = new StringBuilder();
        for (String pkg : selected) {
            if (sb.length() > 0) sb.append(',');
            sb.append(pkg);
        }
        ModulePrefs.get(this).edit()
                .putString(ModulePrefs.KEY_DYNAMIC_APPS, sb.toString())
                .commit();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSelection();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class AppAdapter extends BaseAdapter {
        private final java.util.Map<String, Drawable> icons = new java.util.HashMap<>();

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_dynamic_app, parent, false);
            }
            Entry entry = shown.get(position);
            View card = view.findViewById(R.id.itemCard);
            ImageView icon = view.findViewById(R.id.itemIcon);
            TextView name = view.findViewById(R.id.itemName);
            TextView pkg = view.findViewById(R.id.itemPackage);
            TextView badge = view.findViewById(R.id.itemBadge);
            ImageView check = view.findViewById(R.id.itemCheck);

            card.setBackground(rowBackground);
            name.setText(entry.label);
            name.setTextColor(colorName);
            pkg.setText(entry.pkg);
            pkg.setTextColor(colorPackage);
            badge.setVisibility(entry.recommended ? View.VISIBLE : View.GONE);
            badge.setBackground(badgeBackground);
            badge.setTextColor(colorBadgeText);
            boolean checked = selected.contains(entry.pkg);
            check.setBackground(checked
                    ? getResources().getDrawable(R.drawable.ic_picker_check_on, getTheme())
                    : checkOffBackground);

            // 图标懒加载：只在本行真正要显示时才取，且缓存起来。
            Drawable cached = entry.icon != null ? entry.icon : icons.get(entry.pkg);
            if (cached == null) {
                try {
                    cached = getPackageManager().getApplicationIcon(entry.pkg);
                    icons.put(entry.pkg, cached);
                    entry.icon = cached;
                } catch (Throwable ignored) {
                }
            }
            if (cached != null) icon.setImageDrawable(cached);
            return view;
        }
    }
}
