package com.yuki.yukihub.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Manages dynamic theme colors extracted from the custom background.
 * Caches extracted colors in SharedPreferences for fast cold-start.
 */
public class DynamicTheme {

    public static final String KEY_BG_THEME_ENABLED = "bg_theme_enabled";
    public static final String KEY_CUSTOM_COLOR_ENABLED = "custom_color_enabled";
    public static final String KEY_CUSTOM_COLOR_MODE = "custom_color_mode"; // 0=single, 1=gradient
    public static final String KEY_CUSTOM_COLOR_PRIMARY = "custom_color_primary";
    public static final String KEY_CUSTOM_COLOR_SECONDARY = "custom_color_secondary";
    public static final String KEY_CUSTOM_GRADIENT_ANGLE = "custom_gradient_angle";

    private static final String CACHE_PREFIX = "dtheme_";
    private static final String CACHE_BG = CACHE_PREFIX + "bg";
    private static final String CACHE_BG2 = CACHE_PREFIX + "bg2";
    private static final String CACHE_CARD = CACHE_PREFIX + "card";
    private static final String CACHE_CARD2 = CACHE_PREFIX + "card2";
    private static final String CACHE_PRIMARY = CACHE_PREFIX + "primary";
    private static final String CACHE_SECONDARY = CACHE_PREFIX + "secondary";
    private static final String CACHE_TEXT_MUTED = CACHE_PREFIX + "text_muted";
    private static final String CACHE_LINE = CACHE_PREFIX + "line";
    private static final String CACHE_GLOW1 = CACHE_PREFIX + "glow1";
    private static final String CACHE_GLOW2 = CACHE_PREFIX + "glow2";
    private static final String CACHE_AURORA1 = CACHE_PREFIX + "aurora1";
    private static final String CACHE_AURORA2 = CACHE_PREFIX + "aurora2";
    private static final String CACHE_AURORA3 = CACHE_PREFIX + "aurora3";
    private static final String CACHE_VALID = CACHE_PREFIX + "valid";

    private ThemeColorExtractor.ThemeColors colors = ThemeColorExtractor.DEFAULT;
    private boolean enabled = false;
    private boolean customColorEnabled = false;
    private int customColorMode = 0; // 0=single, 1=gradient
    private int customColorPrimary = 0xFF8AB4FF;
    private int customColorSecondary = 0xFFFF8AB3;
    private float customGradientAngle = 0f;

    private static volatile DynamicTheme instance;

    private DynamicTheme() {}

    @NonNull
    public static DynamicTheme getInstance() {
        if (instance == null) {
            synchronized (DynamicTheme.class) {
                if (instance == null) instance = new DynamicTheme();
            }
        }
        return instance;
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public boolean isCustomColorEnabled() { return customColorEnabled; }
    
    public void setCustomColorEnabled(boolean enabled) { this.customColorEnabled = enabled; }
    
    public int getCustomColorMode() { return customColorMode; }
    
    public void setCustomColorMode(int mode) { this.customColorMode = mode; }
    
    public int getCustomColorPrimary() { return customColorPrimary; }
    
    public void setCustomColorPrimary(int color) { this.customColorPrimary = color; }
    
    public int getCustomColorSecondary() { return customColorSecondary; }
    
    public void setCustomColorSecondary(int color) { this.customColorSecondary = color; }
    
    public float getCustomGradientAngle() { return customGradientAngle; }
    
    public void setCustomGradientAngle(float angle) { this.customGradientAngle = angle; }

    @NonNull
    public ThemeColorExtractor.ThemeColors getColors() {
        if (customColorEnabled && enabled) {
            if (customColorMode == 0) {
                return generateThemeFromSingleColor(customColorPrimary);
            } else {
                return generateThemeFromGradient(customColorPrimary, customColorSecondary);
            }
        }
        return enabled ? colors : ThemeColorExtractor.DEFAULT;
    }
    
    /**
     * 从单个颜色生成完整的主题色系。
     * Alpha 作为"主题强度"：255=完全使用自定义色，0=完全使用默认主题。
     */
    private ThemeColorExtractor.ThemeColors generateThemeFromSingleColor(int baseColor) {
        // 提取 alpha 作为强度（0~1），使用不透明的颜色值
        float strength = Color.alpha(baseColor) / 255f;
        int opaqueColor = 0xFF000000 | (baseColor & 0x00FFFFFF);
        
        // 生成完整自定义主题
        int bg = generateDarkVariant(opaqueColor, 0.1f);
        int bg2 = generateDarkVariant(opaqueColor, 0.15f);
        int card = generateDarkVariant(opaqueColor, 0.2f);
        int card2 = generateDarkVariant(opaqueColor, 0.25f);
        int primary = opaqueColor;
        int secondary = shiftHue(opaqueColor, 0.33f);
        int textMuted = generateLightVariant(opaqueColor, 0.6f);
        int line = generateDarkVariant(opaqueColor, 0.3f);
        int glowColor1 = (0x55 << 24) | (primary & 0x00FFFFFF);
        int glowColor2 = (0x66 << 24) | (secondary & 0x00FFFFFF);
        int auroraColor1 = (0x46 << 24) | (primary & 0x00FFFFFF);
        int auroraColor2 = (0x52 << 24) | (secondary & 0x00FFFFFF);
        int auroraColor3 = (0x24 << 24) | (primary & 0x00FFFFFF);
        
        ThemeColorExtractor.ThemeColors custom = new ThemeColorExtractor.ThemeColors(
                bg, bg2, card, card2, primary, secondary,
                textMuted, line, glowColor1, glowColor2,
                auroraColor1, auroraColor2, auroraColor3
        );
        
        // 根据强度混合默认主题
        return blendThemes(ThemeColorExtractor.DEFAULT, custom, strength);
    }
    
    /**
     * 从渐变色生成主题。
     * Alpha 作为"主题强度"：255=完全使用自定义色，0=完全使用默认主题。
     */
    private ThemeColorExtractor.ThemeColors generateThemeFromGradient(int primaryColor, int secondaryColor) {
        // 提取强度：两个颜色 alpha 取平均
        float strength = ((Color.alpha(primaryColor) + Color.alpha(secondaryColor)) / 2f) / 255f;
        int c1 = 0xFF000000 | (primaryColor & 0x00FFFFFF);
        int c2 = 0xFF000000 | (secondaryColor & 0x00FFFFFF);
        
        // 渐变主题：c1 控制左侧（bg→card→primary），c2 控制右侧（card2→secondary→glow2）
        // 使用更均衡的混合比例让两个颜色都可见
        int bg = generateDarkVariant(c1, 0.1f);
        int bg2 = generateDarkVariant(blend(c1, c2, 0.5f), 0.13f);
        int card = generateDarkVariant(blend(c1, c2, 0.5f), 0.2f);
        int card2 = generateDarkVariant(c2, 0.22f);
        int primary = c1;
        int secondary = c2;
        int textMuted = generateLightVariant(blend(c1, c2, 0.5f), 0.65f);
        int line = blend(generateDarkVariant(c1, 0.3f), generateDarkVariant(c2, 0.3f), 0.5f);
        // glow：双色各自发光
        int glowColor1 = (0x55 << 24) | (c1 & 0x00FFFFFF);
        int glowColor2 = (0x66 << 24) | (c2 & 0x00FFFFFF);
        // aurora：三个极光色，c1 / 混合 / c2 让背景有双色渐变感
        int auroraColor1 = (0x46 << 24) | (c1 & 0x00FFFFFF);
        int auroraColor2 = (0x52 << 24) | (c2 & 0x00FFFFFF);
        int auroraColor3 = (0x38 << 24) | (blend(c1, c2, 0.5f) & 0x00FFFFFF);
        
        ThemeColorExtractor.ThemeColors custom = new ThemeColorExtractor.ThemeColors(
                bg, bg2, card, card2, primary, secondary,
                textMuted, line, glowColor1, glowColor2,
                auroraColor1, auroraColor2, auroraColor3,
                true // isGradient: 渐变模式
        );
        
        // 根据强度混合默认主题
        return blendThemes(ThemeColorExtractor.DEFAULT, custom, strength);
    }
    
    /**
     * 混合两个主题：ratio=0 返回 base，ratio=1 返回 custom
     */
    private ThemeColorExtractor.ThemeColors blendThemes(ThemeColorExtractor.ThemeColors base, 
            ThemeColorExtractor.ThemeColors custom, float ratio) {
        ratio = Math.max(0f, Math.min(1f, ratio));
        if (ratio >= 1f) return custom;
        if (ratio <= 0f) return base;
        
        return new ThemeColorExtractor.ThemeColors(
                blendRGB(base.bg, custom.bg, ratio),
                blendRGB(base.bg2, custom.bg2, ratio),
                blendRGB(base.card, custom.card, ratio),
                blendRGB(base.card2, custom.card2, ratio),
                blendRGB(base.primary, custom.primary, ratio),
                blendRGB(base.secondary, custom.secondary, ratio),
                blendRGB(base.textMuted, custom.textMuted, ratio),
                blendRGB(base.line, custom.line, ratio),
                blendAlpha(base.glowColor1, custom.glowColor1, ratio),
                blendAlpha(base.glowColor2, custom.glowColor2, ratio),
                blendAlpha(base.auroraColor1, custom.auroraColor1, ratio),
                blendAlpha(base.auroraColor2, custom.auroraColor2, ratio),
                blendAlpha(base.auroraColor3, custom.auroraColor3, ratio),
                custom.isGradient
        );
    }
    
    private int blendRGB(int c1, int c2, float ratio) {
        int r = (int) (Color.red(c1) * (1f - ratio) + Color.red(c2) * ratio);
        int g = (int) (Color.green(c1) * (1f - ratio) + Color.green(c2) * ratio);
        int b = (int) (Color.blue(c1) * (1f - ratio) + Color.blue(c2) * ratio);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    
    private int blendAlpha(int c1, int c2, float ratio) {
        int a = (int) (Color.alpha(c1) * (1f - ratio) + Color.alpha(c2) * ratio);
        int r = (int) (Color.red(c1) * (1f - ratio) + Color.red(c2) * ratio);
        int g = (int) (Color.green(c1) * (1f - ratio) + Color.green(c2) * ratio);
        int b = (int) (Color.blue(c1) * (1f - ratio) + Color.blue(c2) * ratio);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    
    private int blend(int c1, int c2, float ratio) {
        ratio = Math.max(0f, Math.min(1f, ratio));
        int a = (int) (Color.alpha(c1) * (1f - ratio) + Color.alpha(c2) * ratio);
        int r = (int) (Color.red(c1) * (1f - ratio) + Color.red(c2) * ratio);
        int g = (int) (Color.green(c1) * (1f - ratio) + Color.green(c2) * ratio);
        int b = (int) (Color.blue(c1) * (1f - ratio) + Color.blue(c2) * ratio);
        return Color.argb(a, r, g, b);
    }
    
    private int generateDarkVariant(int color, float darkness) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0, hsv[2] * darkness);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }
    
    private int generateLightVariant(int color, float lightness) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(1, hsv[2] * lightness);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }
    
    private int shiftHue(int color, float offset) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + offset * 360f) % 360f;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    /**
     * Extract colors from a background image and cache the result.
     * Call this on a background thread.
     */
    @Nullable
    public ThemeColorExtractor.ThemeColors extractAndCache(@NonNull Context context, @NonNull String bgUri) {
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(
                    context.getContentResolver().openInputStream(Uri.parse(bgUri)));
            if (bitmap == null) return null;
            ThemeColorExtractor.ThemeColors extracted = ThemeColorExtractor.extract(bitmap);
            bitmap.recycle();
            if (extracted == null) return null;
            colors = extracted;
            saveCache(context);
            return extracted;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Extract colors from a video texture frame and cache the result.
     */
    @Nullable
    public ThemeColorExtractor.ThemeColors extractFromBitmapAndCache(@NonNull Context context, @NonNull Bitmap bitmap) {
        ThemeColorExtractor.ThemeColors extracted = ThemeColorExtractor.extract(bitmap);
        if (extracted == null) return null;
        colors = extracted;
        saveCache(context);
        return extracted;
    }

    /**
     * Load cached colors from SharedPreferences. Returns true if valid cache exists.
     */
    public boolean loadCache(@NonNull SharedPreferences prefs) {
        if (!prefs.getBoolean(CACHE_VALID, false)) return false;
        colors = new ThemeColorExtractor.ThemeColors(
                prefs.getInt(CACHE_BG, ThemeColorExtractor.DEFAULT.bg),
                prefs.getInt(CACHE_BG2, ThemeColorExtractor.DEFAULT.bg2),
                prefs.getInt(CACHE_CARD, ThemeColorExtractor.DEFAULT.card),
                prefs.getInt(CACHE_CARD2, ThemeColorExtractor.DEFAULT.card2),
                prefs.getInt(CACHE_PRIMARY, ThemeColorExtractor.DEFAULT.primary),
                prefs.getInt(CACHE_SECONDARY, ThemeColorExtractor.DEFAULT.secondary),
                prefs.getInt(CACHE_TEXT_MUTED, ThemeColorExtractor.DEFAULT.textMuted),
                prefs.getInt(CACHE_LINE, ThemeColorExtractor.DEFAULT.line),
                prefs.getInt(CACHE_GLOW1, ThemeColorExtractor.DEFAULT.glowColor1),
                prefs.getInt(CACHE_GLOW2, ThemeColorExtractor.DEFAULT.glowColor2),
                prefs.getInt(CACHE_AURORA1, ThemeColorExtractor.DEFAULT.auroraColor1),
                prefs.getInt(CACHE_AURORA2, ThemeColorExtractor.DEFAULT.auroraColor2),
                prefs.getInt(CACHE_AURORA3, ThemeColorExtractor.DEFAULT.auroraColor3)
        );
        return true;
    }

    public void clearCache(@NonNull SharedPreferences prefs) {
        prefs.edit().remove(CACHE_VALID)
                .remove(CACHE_BG).remove(CACHE_BG2)
                .remove(CACHE_CARD).remove(CACHE_CARD2)
                .remove(CACHE_PRIMARY).remove(CACHE_SECONDARY)
                .remove(CACHE_TEXT_MUTED).remove(CACHE_LINE)
                .remove(CACHE_GLOW1).remove(CACHE_GLOW2)
                .remove(CACHE_AURORA1).remove(CACHE_AURORA2).remove(CACHE_AURORA3)
                .apply();
        colors = ThemeColorExtractor.DEFAULT;
    }

    private void saveCache(@NonNull Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("yukihub_prefs", Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(CACHE_VALID, true)
                .putInt(CACHE_BG, colors.bg)
                .putInt(CACHE_BG2, colors.bg2)
                .putInt(CACHE_CARD, colors.card)
                .putInt(CACHE_CARD2, colors.card2)
                .putInt(CACHE_PRIMARY, colors.primary)
                .putInt(CACHE_SECONDARY, colors.secondary)
                .putInt(CACHE_TEXT_MUTED, colors.textMuted)
                .putInt(CACHE_LINE, colors.line)
                .putInt(CACHE_GLOW1, colors.glowColor1)
                .putInt(CACHE_GLOW2, colors.glowColor2)
                .putInt(CACHE_AURORA1, colors.auroraColor1)
                .putInt(CACHE_AURORA2, colors.auroraColor2)
                .putInt(CACHE_AURORA3, colors.auroraColor3)
                .apply();
    }
    
    /**
     * 保存自定义颜色设置
     */
    public void saveCustomColorSettings(@NonNull Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("yukihub_prefs", Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_CUSTOM_COLOR_ENABLED, customColorEnabled)
                .putInt(KEY_CUSTOM_COLOR_MODE, customColorMode)
                .putInt(KEY_CUSTOM_COLOR_PRIMARY, customColorPrimary)
                .putInt(KEY_CUSTOM_COLOR_SECONDARY, customColorSecondary)
                .putFloat(KEY_CUSTOM_GRADIENT_ANGLE, customGradientAngle)
                .apply();
    }
    
    /**
     * 加载自定义颜色设置
     */
    public void loadCustomColorSettings(@NonNull SharedPreferences prefs) {
        customColorEnabled = prefs.getBoolean(KEY_CUSTOM_COLOR_ENABLED, false);
        customColorMode = prefs.getInt(KEY_CUSTOM_COLOR_MODE, 0);
        customColorPrimary = prefs.getInt(KEY_CUSTOM_COLOR_PRIMARY, 0xFF8AB4FF);
        customColorSecondary = prefs.getInt(KEY_CUSTOM_COLOR_SECONDARY, 0xFFFF8AB3);
        customGradientAngle = prefs.getFloat(KEY_CUSTOM_GRADIENT_ANGLE, 0f);
    }

    // Convenience accessors
    public int bg() { return getColors().bg; }
    public int bg2() { return getColors().bg2; }
    public int card() { return getColors().card; }
    public int card2() { return getColors().card2; }
    public int primary() { return getColors().primary; }
    public int secondary() { return getColors().secondary; }
    public int textMuted() { return getColors().textMuted; }
    public int line() { return getColors().line; }
}
