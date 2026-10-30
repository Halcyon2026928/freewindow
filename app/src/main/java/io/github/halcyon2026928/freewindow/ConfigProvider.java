package io.github.halcyon2026928.freewindow;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 给 system_server 的即时读取通道。重启后的权威数据在 XSharedPreferences。
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.halcyon2026928.freewindow.config";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        SharedPreferences prefs = ModulePrefs.get(getContext());
        MatrixCursor c = new MatrixCursor(new String[]{"key", "value"});
        c.addRow(new Object[]{SettingsActivity.KEY_HIDE_BOTTOM_BAR,
                prefs.getBoolean(SettingsActivity.KEY_HIDE_BOTTOM_BAR, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_HIDE_THREE_DOT,
                prefs.getBoolean(SettingsActivity.KEY_HIDE_THREE_DOT, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_ENABLE_DOCK,
                prefs.getBoolean(SettingsActivity.KEY_ENABLE_DOCK, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_ENABLE_LIVE,
                prefs.getBoolean(SettingsActivity.KEY_ENABLE_LIVE, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_ENABLE_HANGUP,
                prefs.getBoolean(SettingsActivity.KEY_ENABLE_HANGUP, true) ? 1 : 0});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.freewindow.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
