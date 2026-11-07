package com.fsheartcast.osc;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** 语言切换：对应桌面版 AppResources.SetLanguage（简体 / 繁體 / English / 日本語）。 */
public final class Lang {

    public static final String[] VALUES = { "system", "zh-Hans", "zh-Hant", "en", "ja" };

    private Lang() {
    }

    public static Context wrap(Context base) {
        String code = Prefs.language(base);
        if (code == null || code.length() == 0 || "system".equals(code)) {
            return base;
        }
        Locale locale = toLocale(code);
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    public static Locale toLocale(String code) {
        if (code == null) {
            return Locale.getDefault();
        }
        if (code.startsWith("zh")) {
            return code.contains("Hant") ? Locale.TRADITIONAL_CHINESE : Locale.SIMPLIFIED_CHINESE;
        }
        if ("ja".equals(code)) {
            return Locale.JAPANESE;
        }
        if ("en".equals(code)) {
            return Locale.ENGLISH;
        }
        return Locale.getDefault();
    }

    public static int indexOf(String code) {
        if (code == null) {
            return 0;
        }
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i].equals(code)) {
                return i;
            }
        }
        return 0;
    }
}
