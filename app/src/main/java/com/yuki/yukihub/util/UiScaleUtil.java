package com.yuki.yukihub.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

public final class UiScaleUtil {
    public static final String PREFS_NAME = "yukihub_prefs";
    public static final String KEY_UI_FONT_SCALE = "ui_font_scale";
    public static final String KEY_UI_SCALE = "ui_scale";
    public static final float DEFAULT_FONT_SCALE = 1.0f;
    public static final float MIN_FONT_SCALE = 0.85f;
    public static final float MAX_FONT_SCALE = 1.30f;
    public static final float DEFAULT_UI_SCALE = 1.0f;
    public static final float MIN_UI_SCALE = 0.70f;
    public static final float MAX_UI_SCALE = 1.50f;

    private UiScaleUtil() { }

    public static float getFontScale(Context context) {
        if (context == null) return DEFAULT_FONT_SCALE;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return clamp(prefs.getFloat(KEY_UI_FONT_SCALE, DEFAULT_FONT_SCALE));
    }

    public static void setFontScale(Context context, float scale) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_UI_FONT_SCALE, clamp(scale))
                .apply();
    }

    public static void resetFontScale(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_UI_FONT_SCALE)
                .apply();
    }

    public static float getUiScale(Context context) {
        if (context == null) return DEFAULT_UI_SCALE;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return clampUiScale(prefs.getFloat(KEY_UI_SCALE, DEFAULT_UI_SCALE));
    }

    public static void setUiScale(Context context, float scale) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_UI_SCALE, clampUiScale(scale))
                .apply();
    }

    public static float clampUiScale(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return DEFAULT_UI_SCALE;
        return Math.max(MIN_UI_SCALE, Math.min(MAX_UI_SCALE, value));
    }

    public static int uiScalePercent(float scale) {
        return Math.round(clampUiScale(scale) * 100f);
    }

    public static Context wrap(Context base) {
        if (base == null) return null;
        float fontScale = getFontScale(base);
        float uiScale = getUiScale(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale = fontScale;
        // 通过修改 densityDpi 实现全局UI缩放
        if (uiScale != 1.0f) {
            config.densityDpi = (int) (config.densityDpi * uiScale);
        }
        return base.createConfigurationContext(config);
    }

    public static float clamp(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return DEFAULT_FONT_SCALE;
        return Math.max(MIN_FONT_SCALE, Math.min(MAX_FONT_SCALE, value));
    }

    public static int percent(float scale) {
        return Math.round(clamp(scale) * 100f);
    }
}
