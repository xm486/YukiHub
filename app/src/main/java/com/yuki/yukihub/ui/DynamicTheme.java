package com.yuki.yukihub.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Manages dynamic theme colors extracted from the custom background.
 * Caches extracted colors in SharedPreferences for fast cold-start.
 */
public class DynamicTheme {

    public static final String KEY_BG_THEME_ENABLED = "bg_theme_enabled";

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

    @NonNull
    public ThemeColorExtractor.ThemeColors getColors() {
        return enabled ? colors : ThemeColorExtractor.DEFAULT;
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
