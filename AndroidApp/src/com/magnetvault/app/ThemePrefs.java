package com.magnetvault.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** 轻量设置存储（SharedPreferences）。 */
public final class ThemePrefs {

    public static final int FOLLOW = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREFS = "mv_settings";
    private static final String KEY_THEME = "themeMode";
    private static final String KEY_DEVICE = "deviceId";
    private static final String KEY_TRASH_DAYS = "trashKeepDays";
    private static final String KEY_PWD_SET = "backupPasswordSet";
    private static final String KEY_DAV_URL = "davUrl";
    private static final String KEY_DAV_USER = "davUser";
    private static final String KEY_DAV_DIR = "davDir";
    private static final String KEY_DAV_LAST = "davLastResult";
    private static final String KEY_LAST_BACKUP = "lastBackupAt";
    private static final String KEY_AUTO_BACKUP = "autoBackup";
    private static final String KEY_COMPACT_LIST = "compactList";

    public static final String SECRET_DAV_PASSWORD = "davPassword";

    private ThemePrefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static android.content.Context themedContext(android.content.Context base) {
        int mode = getMode(base);
        if (mode == FOLLOW) return base;
        android.content.res.Configuration config =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        config.uiMode =
                (config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        | (mode == DARK
                                ? android.content.res.Configuration.UI_MODE_NIGHT_YES
                                : android.content.res.Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(config);
    }

    public static void apply(Activity activity) {
        activity.setTheme(R.style.AppTheme);
    }

    public static int getMode(Context ctx) {
        return prefs(ctx).getInt(KEY_THEME, FOLLOW);
    }

    public static void setMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(KEY_THEME, mode).apply();
    }

    public static String themeLabel(Context ctx) {
        int mode = getMode(ctx);
        if (mode == LIGHT) {
            return "霜银浅色";
        }
        if (mode == DARK) {
            return "暗夜黑曜";
        }
        return "跟随系统";
    }

    public static boolean isCompactList(Context ctx) {
        return prefs(ctx).getBoolean(KEY_COMPACT_LIST, true);
    }

    public static void setCompactList(Context ctx, boolean compact) {
        prefs(ctx).edit().putBoolean(KEY_COMPACT_LIST, compact).apply();
    }

    public static String compactListLabel(Context ctx) {
        return isCompactList(ctx) ? "紧凑（默认 2 行）" : "完整（显示全名）";
    }

    public static String deviceId(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String id = sp.getString(KEY_DEVICE, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            sp.edit().putString(KEY_DEVICE, id).apply();
        }
        return id;
    }

    public static int trashKeepDays(Context ctx) {
        return prefs(ctx).getInt(KEY_TRASH_DAYS, 30);
    }

    public static void setTrashKeepDays(Context ctx, int days) {
        prefs(ctx).edit().putInt(KEY_TRASH_DAYS, days).apply();
    }

    /** 只记录"用户已设置过备份密码"，不存密码本身、也不存任何可验证的哈希。 */
    public static boolean isBackupPasswordSet(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PWD_SET, false);
    }

    public static void setBackupPasswordSet(Context ctx, boolean set) {
        prefs(ctx).edit().putBoolean(KEY_PWD_SET, set).apply();
    }

    // ==================== 坚果云 WebDAV 配置 ====================

    public static String davUrl(Context ctx) {
        return prefs(ctx).getString(KEY_DAV_URL, WebDavClient.DEFAULT_BASE);
    }

    public static String davUser(Context ctx) {
        return prefs(ctx).getString(KEY_DAV_USER, "");
    }

    public static String davDir(Context ctx) {
        return prefs(ctx).getString(KEY_DAV_DIR, WebDavClient.DEFAULT_DIR);
    }

    public static void setDav(Context ctx, String url, String user, String dir) {
        prefs(ctx)
                .edit()
                .putString(KEY_DAV_URL, WebDavClient.normalizeBase(url))
                .putString(KEY_DAV_USER, user)
                .putString(KEY_DAV_DIR, dir)
                .apply();
    }

    public static String davLastResult(Context ctx) {
        return prefs(ctx).getString(KEY_DAV_LAST, "尚未测试过连接");
    }

    public static void setDavLastResult(Context ctx, String text) {
        prefs(ctx).edit().putString(KEY_DAV_LAST, text).apply();
    }

    /** 是否已配置账号 + 密码（用于设置页显示状态）。 */
    public static boolean isDavConfigured(Context ctx) {
        boolean hasUser = davUser(ctx).length() > 0;
        boolean hasPwd = SecretStore.has(ctx, SECRET_DAV_PASSWORD);
        return hasUser && hasPwd;
    }

    // ==================== 每日自动备份 ====================

    public static boolean autoBackup(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_BACKUP, false);
    }

    public static void setAutoBackup(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_BACKUP, on).apply();
    }

    public static long lastBackupAt(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_BACKUP, 0);
    }

    public static void setLastBackupAt(Context ctx, long ts) {
        prefs(ctx).edit().putLong(KEY_LAST_BACKUP, ts).apply();
    }
}
