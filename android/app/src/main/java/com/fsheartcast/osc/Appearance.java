package com.fsheartcast.osc;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/**
 * 主题与强调色：对应桌面版 ThemeService（System/Light/Dark + 6 种强调色）。
 * Android 端用「主题样式」实现，配色来自桌面版 Themes/Dark.xaml、Light.xaml。
 */
public final class Appearance {

    public static final String[] ACCENTS = { "teal", "blue", "purple", "green", "orange", "pink" };
    public static final int[] ACCENT_LABELS = {
            R.string.accent_teal, R.string.accent_blue, R.string.accent_purple,
            R.string.accent_green, R.string.accent_orange, R.string.accent_pink
    };

    private Appearance() {
    }

    public static boolean isDark(Context context) {
        String theme = Prefs.theme(context);
        if ("light".equals(theme)) {
            return false;
        }
        if ("dark".equals(theme)) {
            return true;
        }
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int styleRes(Context context) {
        int accent = accentIndex(Prefs.accent(context));
        if (isDark(context)) {
            switch (accent) {
                case 1:
                    return R.style.AppTheme_Dark_Blue;
                case 2:
                    return R.style.AppTheme_Dark_Purple;
                case 3:
                    return R.style.AppTheme_Dark_Green;
                case 4:
                    return R.style.AppTheme_Dark_Orange;
                case 5:
                    return R.style.AppTheme_Dark_Pink;
                default:
                    return R.style.AppTheme_Dark_Teal;
            }
        }
        switch (accent) {
            case 1:
                return R.style.AppTheme_Light_Blue;
            case 2:
                return R.style.AppTheme_Light_Purple;
            case 3:
                return R.style.AppTheme_Light_Green;
            case 4:
                return R.style.AppTheme_Light_Orange;
            case 5:
                return R.style.AppTheme_Light_Pink;
            default:
                return R.style.AppTheme_Light_Teal;
        }
    }

    public static void apply(Activity activity) {
        activity.setTheme(styleRes(activity));
        // 主题切换后同步窗口背景与系统栏颜色，避免浅色主题下出现深色底闪一下
        try {
            android.util.TypedValue value = new android.util.TypedValue();
            if (activity.getTheme().resolveAttribute(R.attr.bg, value, true)) {
                android.graphics.drawable.ColorDrawable background =
                        new android.graphics.drawable.ColorDrawable(value.data);
                activity.getWindow().setBackgroundDrawable(background);
                activity.getWindow().setStatusBarColor(value.data);
                activity.getWindow().setNavigationBarColor(value.data);
            }
        } catch (Exception ignored) {
            // 窗口尚未创建时忽略
        }
    }

    public static int accentIndex(String accent) {
        if (accent == null) {
            return 0;
        }
        for (int i = 0; i < ACCENTS.length; i++) {
            if (ACCENTS[i].equals(accent)) {
                return i;
            }
        }
        return 0;
    }
}
