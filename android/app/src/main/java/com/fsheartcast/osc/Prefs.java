package com.fsheartcast.osc;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置持久化（SharedPreferences）。
 * OSC 字段与桌面版 HOSC/Services/AppSettings.cs 保持一致；
 * theme / accent / language 对应桌面版的外观设置。
 */
public final class Prefs {

    private static final String FILE = "settings";

    public static final String KEY_IP = "osc_ip";
    public static final String KEY_PORT = "osc_port";
    public static final String KEY_HR = "osc_hr_address";
    public static final String KEY_BEAT = "osc_beat_address";
    public static final String KEY_OSC_ENABLED = "osc_enabled";
    public static final String KEY_AUTO_CONNECT = "auto_connect";

    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_THEME = "theme";
    public static final String KEY_ACCENT = "accent";

    public static final String KEY_AUTO_SCAN = "auto_start_scan";
    public static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String KEY_BACKGROUND = "background_run";
    public static final String KEY_BOOT_START = "boot_start";
    public static final String KEY_UPDATE_CHECKED_AT = "update_checked_at";
    public static final String KEY_UPDATE_IGNORED_TAG = "update_ignored_tag";

    public static final String DEFAULT_IP = "192.168.1.100";
    public static final int DEFAULT_PORT = 9000;
    public static final String DEFAULT_HR = OscParams.DEFAULT_HR_ADDRESS;
    public static final String DEFAULT_BEAT = OscParams.DEFAULT_BEAT_ADDRESS;

    private Prefs() {
    }

    public static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static void putString(Context context, String key, String value) {
        sp(context).edit().putString(key, value).apply();
    }

    private static void putBoolean(Context context, String key, boolean value) {
        sp(context).edit().putBoolean(key, value).apply();
    }

    public static String ip(Context context) {
        String v = sp(context).getString(KEY_IP, DEFAULT_IP);
        return v == null ? DEFAULT_IP : v.trim();
    }

    public static void setIp(Context context, String value) {
        putString(context, KEY_IP, value == null ? "" : value.trim());
    }

    public static int port(Context context) {
        int v = sp(context).getInt(KEY_PORT, DEFAULT_PORT);
        return (v >= 1 && v <= 65535) ? v : DEFAULT_PORT;
    }

    public static void setPort(Context context, int value) {
        sp(context).edit().putInt(KEY_PORT, value).apply();
    }

    public static String hrAddress(Context context) {
        String v = sp(context).getString(KEY_HR, DEFAULT_HR);
        return (v == null || v.length() == 0) ? DEFAULT_HR : v;
    }

    public static void setHrAddress(Context context, String value) {
        putString(context, KEY_HR, value == null ? "" : value.trim());
    }

    public static String beatAddress(Context context) {
        String v = sp(context).getString(KEY_BEAT, DEFAULT_BEAT);
        return (v == null || v.length() == 0) ? DEFAULT_BEAT : v;
    }

    public static void setBeatAddress(Context context, String value) {
        putString(context, KEY_BEAT, value == null ? "" : value.trim());
    }

    public static boolean oscEnabled(Context context) {
        return sp(context).getBoolean(KEY_OSC_ENABLED, true);
    }

    public static void setOscEnabled(Context context, boolean value) {
        putBoolean(context, KEY_OSC_ENABLED, value);
    }

    public static boolean autoConnect(Context context) {
        return sp(context).getBoolean(KEY_AUTO_CONNECT, true);
    }

    public static void setAutoConnect(Context context, boolean value) {
        putBoolean(context, KEY_AUTO_CONNECT, value);
    }

    // ---------------------------------------------------------- 外观

    public static String language(Context context) {
        String v = sp(context).getString(KEY_LANGUAGE, "system");
        return v == null ? "system" : v;
    }

    public static void setLanguage(Context context, String value) {
        putString(context, KEY_LANGUAGE, value);
    }

    public static String theme(Context context) {
        String v = sp(context).getString(KEY_THEME, "system");
        return v == null ? "system" : v;
    }

    public static void setTheme(Context context, String value) {
        putString(context, KEY_THEME, value);
    }

    public static String accent(Context context) {
        String v = sp(context).getString(KEY_ACCENT, "teal");
        return v == null ? "teal" : v;
    }

    public static void setAccent(Context context, String value) {
        putString(context, KEY_ACCENT, value);
    }

    // ---------------------------------------------------------- 行为

    public static boolean autoScan(Context context) {
        return sp(context).getBoolean(KEY_AUTO_SCAN, true);
    }

    public static void setAutoScan(Context context, boolean value) {
        putBoolean(context, KEY_AUTO_SCAN, value);
    }

    public static boolean keepScreenOn(Context context) {
        return sp(context).getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    public static void setKeepScreenOn(Context context, boolean value) {
        putBoolean(context, KEY_KEEP_SCREEN_ON, value);
    }

    public static boolean backgroundRun(Context context) {
        return sp(context).getBoolean(KEY_BACKGROUND, true);
    }

    public static void setBackgroundRun(Context context, boolean value) {
        putBoolean(context, KEY_BACKGROUND, value);
    }

    public static boolean bootStart(Context context) {
        return sp(context).getBoolean(KEY_BOOT_START, false);
    }

    public static void setBootStart(Context context, boolean value) {
        putBoolean(context, KEY_BOOT_START, value);
    }

    // ---------------------------------------------------------- 更新检查

    public static long updateCheckedAt(Context context) {
        return sp(context).getLong(KEY_UPDATE_CHECKED_AT, 0L);
    }

    public static void setUpdateCheckedAt(Context context, long value) {
        sp(context).edit().putLong(KEY_UPDATE_CHECKED_AT, value).apply();
    }

    public static String updateIgnoredTag(Context context) {
        String v = sp(context).getString(KEY_UPDATE_IGNORED_TAG, "");
        return v == null ? "" : v;
    }

    public static void setUpdateIgnoredTag(Context context, String value) {
        putString(context, KEY_UPDATE_IGNORED_TAG, value == null ? "" : value);
    }

    /** 粗略校验主机名/IP，避免在主线程做 DNS 解析。 */
    public static boolean isValidHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim();
        if (h.length() == 0 || h.length() > 253) {
            return false;
        }
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
