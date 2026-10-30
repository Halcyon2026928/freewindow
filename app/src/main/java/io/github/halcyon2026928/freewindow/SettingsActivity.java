package io.github.halcyon2026928.freewindow;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * freewindow 设置界面。
 * 功能开关写入 LSPosed 可读 SharedPreferences，系统框架重启后仍保持上次状态。
 * 主题单独存，不影响功能开关。
 */
public class SettingsActivity extends Activity {

    public static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    public static final String KEY_HIDE_THREE_DOT = "hide_three_dot_menu";
    public static final String KEY_ENABLE_DOCK = "enable_float_dock";
    public static final String KEY_ENABLE_LIVE = "enable_live_preview";
    public static final String KEY_ENABLE_HANGUP = "enable_background_run";

    private static final String UI_PREFS = "ui_prefs";
    private static final String KEY_LIGHT_THEME = "light_theme";

    private Switch bottomBarSwitch;
    private Switch threeDotSwitch;
    private Switch dockSwitch;
    private Switch liveSwitch;
    private Switch hangupSwitch;
    private boolean syncing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final boolean light = isLightTheme();
        setTheme(light ? R.style.LightTheme : R.style.AppTheme);
        setContentView(R.layout.activity_settings);

        View btnTheme = findViewById(R.id.btnTheme);
        ImageView ivThemeIcon = findViewById(R.id.ivThemeIcon);
        ivThemeIcon.setImageResource(light ? R.drawable.ic_theme_sun : R.drawable.ic_theme_moon);
        btnTheme.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_LIGHT_THEME, !light).commit();
                recreate();
            }
        });

        bottomBarSwitch = findViewById(R.id.switch_bottom_bar);
        threeDotSwitch = findViewById(R.id.switch_three_dot);
        dockSwitch = findViewById(R.id.switch_dock);
        liveSwitch = findViewById(R.id.switch_live);
        hangupSwitch = findViewById(R.id.switch_hangup);

        findViewById(R.id.btnDynamicApps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this,
                        DynamicAppsActivity.class));
            }
        });

        syncFromPrefs();

        bottomBarSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_HIDE_BOTTOM_BAR, isChecked);
            }
        });
        threeDotSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_HIDE_THREE_DOT, isChecked);
            }
        });
        dockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_ENABLE_DOCK, isChecked);
            }
        });
        liveSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_ENABLE_LIVE, isChecked);
            }
        });
        hangupSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_ENABLE_HANGUP, isChecked);
            }
        });
    }

    private void syncFromPrefs() {
        syncing = true;
        bottomBarSwitch.setChecked(readPref(KEY_HIDE_BOTTOM_BAR, true));
        threeDotSwitch.setChecked(readPref(KEY_HIDE_THREE_DOT, true));
        dockSwitch.setChecked(readPref(KEY_ENABLE_DOCK, true));
        liveSwitch.setChecked(readPref(KEY_ENABLE_LIVE, true));
        hangupSwitch.setChecked(readPref(KEY_ENABLE_HANGUP, true));
        syncing = false;
    }

    private boolean readPref(String key, boolean def) {
        return ModulePrefs.get(this).getBoolean(key, def);
    }

    private void writePref(String key, boolean value) {
        ModulePrefs.get(this).edit().putBoolean(key, value).commit();
    }

    private boolean isLightTheme() {
        return getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIGHT_THEME, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDynamicSummary();
    }

    private void updateDynamicSummary() {
        String csv = ModulePrefs.get(this).getString(ModulePrefs.KEY_DYNAMIC_APPS, "");
        int count = 0;
        if (csv != null) {
            for (String item : csv.split(",")) {
                if (!item.isEmpty()) count++;
            }
        }
        TextView summary = findViewById(R.id.tvDynamicSummary);
        if (summary != null) {
            summary.setText(count == 0
                    ? "未选择：按内置名单自动判断"
                    : "已选择 " + count + " 个应用");
        }
    }
}
