package com.yuki.yukihub.ui;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

/**
 * Extracts a cohesive set of UI colors from a background bitmap using Palette.
 * Maps extracted colors to YukiHub's semantic color system.
 */
public class ThemeColorExtractor {

    public static class ThemeColors {
        public final int bg;          // yh_bg — deepest background
        public final int bg2;         // yh_bg_2 — secondary background
        public final int card;        // yh_card — card background
        public final int card2;       // yh_card_2 — card secondary
        public final int primary;     // yh_primary — accent / interactive
        public final int secondary;   // yh_secondary — secondary accent
        public final int textMuted;   // yh_text_muted — muted text
        public final int line;        // yh_line — divider / border
        public final int glowColor1;  // CardGlowView gradient color 1
        public final int glowColor2;  // CardGlowView gradient color 2
        public final int auroraColor1; // DynamicSnowBackgroundView aurora 1
        public final int auroraColor2; // DynamicSnowBackgroundView aurora 2
        public final int auroraColor3; // DynamicSnowBackgroundView aurora 3

        public ThemeColors(int bg, int bg2, int card, int card2, int primary, int secondary,
                           int textMuted, int line, int glowColor1, int glowColor2,
                           int auroraColor1, int auroraColor2, int auroraColor3) {
            this.bg = bg;
            this.bg2 = bg2;
            this.card = card;
            this.card2 = card2;
            this.primary = primary;
            this.secondary = secondary;
            this.textMuted = textMuted;
            this.line = line;
            this.glowColor1 = glowColor1;
            this.glowColor2 = glowColor2;
            this.auroraColor1 = auroraColor1;
            this.auroraColor2 = auroraColor2;
            this.auroraColor3 = auroraColor3;
        }
    }

    // Default YukiHub dark-blue theme
    public static final ThemeColors DEFAULT = new ThemeColors(
            0xFF0B1020, 0xFF111936, 0xFF171E33, 0xFF222B49,
            0xFF8AB4FF, 0xFFFF8AB3, 0xFF9AA4BF, 0xFF2D3658,
            0x5AC8FA, 0xAF52DE,
            0x465AC8FA, 0x52AF52DE, 0x243C6CFF
    );

    @Nullable
    public static ThemeColors extract(@NonNull Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        Palette palette = Palette.from(bitmap)
                .resizeBitmapArea(100 * 100)
                .generate();

        Palette.Swatch vibrant = palette.getVibrantSwatch();
        Palette.Swatch muted = palette.getMutedSwatch();
        Palette.Swatch darkVibrant = palette.getDarkVibrantSwatch();
        Palette.Swatch darkMuted = palette.getDarkMutedSwatch();
        Palette.Swatch dominant = palette.getDominantSwatch();

        if (darkMuted == null && darkVibrant == null && dominant == null) return null;

        // Base background: darkest available
        int bg = pickDarkest(darkMuted, darkVibrant, dominant);
        bg = ensureMinBrightness(bg, 0.03f); // at least very dark

        // Secondary background: slightly brighter
        int bg2 = shiftBrightness(bg, 1.25f);

        // Card backgrounds
        int card = shiftBrightness(bg, 1.50f);
        int card2 = shiftBrightness(bg, 1.85f);

        // Primary accent: vibrant or muted, boosted for readability
        int primary;
        if (vibrant != null) {
            primary = ensureMinBrightness(vibrant.getRgb(), 0.55f);
        } else if (muted != null) {
            primary = ensureMinBrightness(muted.getRgb(), 0.55f);
        } else if (dominant != null) {
            primary = ensureMinBrightness(dominant.getRgb(), 0.55f);
        } else {
            primary = DEFAULT.primary;
        }

        // Secondary accent: complementary shift from primary
        int secondary = shiftHue(primary, 0.33f);
        secondary = ensureMinBrightness(secondary, 0.55f);

        // Muted text
        int textMuted = shiftBrightness(bg, 3.0f);
        textMuted = clampBrightness(textMuted, 0.35f, 0.65f);

        // Line / border
        int line = shiftBrightness(bg, 1.75f);

        // Glow colors derived from primary/secondary
        int glowColor1 = (0x55 << 24) | (primary & 0x00FFFFFF);
        int glowColor2 = (0x66 << 24) | (secondary & 0x00FFFFFF);

        // Aurora colors
        int auroraColor1 = (0x46 << 24) | (primary & 0x00FFFFFF);
        int auroraColor2 = (0x52 << 24) | (secondary & 0x00FFFFFF);
        int auroraColor3 = (0x24 << 24) | (primary & 0x00FFFFFF);

        return new ThemeColors(bg, bg2, card, card2, primary, secondary, textMuted,
                line, glowColor1, glowColor2, auroraColor1, auroraColor2, auroraColor3);
    }

    private static int pickDarkest(Palette.Swatch... swatches) {
        int darkest = 0xFFFFFFFF;
        float minLum = 1f;
        for (Palette.Swatch s : swatches) {
            if (s == null) continue;
            float lum = luminance(s.getRgb());
            if (lum < minLum) {
                minLum = lum;
                darkest = s.getRgb();
            }
        }
        return darkest;
    }

    private static float luminance(int color) {
        float r = Color.red(color) / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static int shiftBrightness(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(1f, hsv[2] * factor);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private static int ensureMinBrightness(int color, float minLum) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[2] < minLum) hsv[2] = minLum;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private static int clampBrightness(int color, float minLum, float maxLum) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(minLum, Math.min(maxLum, hsv[2]));
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private static int shiftHue(int color, float offset) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + offset * 360f) % 360f;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }
}
