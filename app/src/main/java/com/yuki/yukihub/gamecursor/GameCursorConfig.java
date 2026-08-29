package com.yuki.yukihub.gamecursor;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

/**
 * 游戏内虚拟鼠标光标配置：KRKR / Artemis 两引擎共用。
 *
 * 存放在 app 自己的 SharedPreferences("game_cursor") 里，
 * 与 KRKR 的 Kirikiroid2Preference.xml（含 vcursor_scale）以及
 * Artemis 引擎的任何配置完全独立，互不影响。
 */
public final class GameCursorConfig {
    public static final String PREFS_NAME = "game_cursor";
    public static final String KEY_ENABLED_KRKR = "enabled_krkr";
    public static final String KEY_ENABLED_ARTEMIS = "enabled_artemis";
    /** 光标图标缩放，1.0 = 基准 60dp 高。 */
    public static final String KEY_SCALE = "scale";
    /** 光标不透明度 0.2~1.0。 */
    public static final String KEY_ALPHA = "alpha";
    /** 自定义光标图标（本地文件绝对路径或 content Uri 字符串）；空 = 内置箭头。 */
    public static final String KEY_ICON_URI = "icon_uri";
    /**
     * Windows 光标包（.ani/.cur）方案名。非空表示已安装光标包，
     * 优先级高于 KEY_ICON_URI 的 PNG。
     */
    public static final String KEY_WIN_CURSOR_NAME = "win_cursor_name";
    /** 鼠标模式下的移动灵敏度（手指位移 × 该系数 = 光标位移）。 */
    public static final String KEY_SENSITIVITY = "sensitivity";
    /** 上次使用的模式：true = 鼠标模式，false = 直接触摸。 */
    public static final String KEY_MOUSE_MODE = "mouse_mode";

    public static final float DEFAULT_SCALE = 0.6f;
    public static final float DEFAULT_ALPHA = 0.85f;
    public static final float DEFAULT_SENSITIVITY = 1.2f;
    /**
     * 缩放下限。
     *
     * 基准高度 60dp，所以 0.12 → 约 7dp，接近桌面鼠标指针的观感。
     * 之前是 0.3（18dp），实测调到最小仍偏大。
     */
    public static final float MIN_SCALE = 0.12f;
    public static final float MAX_SCALE = 2.0f;

    public boolean krkrEnabled;
    public boolean artemisEnabled;
    public float scale = DEFAULT_SCALE;
    public float alpha = DEFAULT_ALPHA;
    public float sensitivity = DEFAULT_SENSITIVITY;
    public boolean mouseMode;
    public String iconUri;
    /** 已安装光标包的方案名；空 = 未安装。 */
    public String winCursorName;

    public static GameCursorConfig load(Context ctx) {
        GameCursorConfig c = new GameCursorConfig();
        if (ctx == null) return c;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            c.krkrEnabled = p.getBoolean(KEY_ENABLED_KRKR, false);
            c.artemisEnabled = p.getBoolean(KEY_ENABLED_ARTEMIS, false);
            c.scale = clamp(p.getFloat(KEY_SCALE, DEFAULT_SCALE), MIN_SCALE, MAX_SCALE);
            c.alpha = clamp(p.getFloat(KEY_ALPHA, DEFAULT_ALPHA), 0.2f, 1.0f);
            c.sensitivity = clamp(p.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY), 0.5f, 3.0f);
            c.mouseMode = p.getBoolean(KEY_MOUSE_MODE, false);
            c.iconUri = p.getString(KEY_ICON_URI, null);
            c.winCursorName = p.getString(KEY_WIN_CURSOR_NAME, null);
        } catch (Throwable ignored) { }
        return c;
    }

    public void save(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ENABLED_KRKR, krkrEnabled)
                    .putBoolean(KEY_ENABLED_ARTEMIS, artemisEnabled)
                    .putFloat(KEY_SCALE, scale)
                    .putFloat(KEY_ALPHA, alpha)
                    .putFloat(KEY_SENSITIVITY, sensitivity)
                    .putBoolean(KEY_MOUSE_MODE, mouseMode)
                    .putString(KEY_ICON_URI, iconUri)
                    .putString(KEY_WIN_CURSOR_NAME, winCursorName)
                    .apply();
        } catch (Throwable ignored) { }
    }

    /** 是否已安装 Windows 光标包。 */
    public boolean hasWinCursor() {
        return winCursorName != null && !winCursorName.trim().isEmpty();
    }

    /**
     * 只记录模式切换，不动其它字段。
     * 模式可持久化是安全的：模式切换按钮在两种模式下都常驻可见，
     * 不会出现"开了模式又找不到入口"的死锁（ONS 收起键那次的教训）。
     */
    public static void saveMouseMode(Context ctx, boolean mouseMode) {
        if (ctx == null) return;
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_MOUSE_MODE, mouseMode).apply();
        } catch (Throwable ignored) { }
    }

    /** 清除自定义图标，回落到内置箭头。 */
    public void clearIcon(Context ctx) {
        iconUri = null;
        save(ctx);
    }

    /**
     * 加载自定义光标 Bitmap，限制最大边 512px（超限自动降采样）。
     * 加载失败返回 null，由上层回落到内置箭头。
     */
    public Bitmap loadIcon(Context ctx) {
        if (ctx == null || iconUri == null || iconUri.trim().isEmpty()) return null;
        try {
            return decodeScaled(ctx, iconUri.trim(), 512);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decodeScaled(Context ctx, String value, int maxSize) {
        Uri uri = Uri.parse(value);
        boolean isFile = "file".equals(uri.getScheme()) || value.startsWith("/");
        String filePath = "file".equals(uri.getScheme()) ? uri.getPath() : value;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (isFile) {
            BitmapFactory.decodeFile(filePath, bounds);
        } else {
            java.io.InputStream is = null;
            try {
                is = ctx.getContentResolver().openInputStream(uri);
                BitmapFactory.decodeStream(is, null, bounds);
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(is);
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        if (isFile) {
            return BitmapFactory.decodeFile(filePath, opts);
        }
        java.io.InputStream is = null;
        try {
            is = ctx.getContentResolver().openInputStream(uri);
            return BitmapFactory.decodeStream(is, null, opts);
        } catch (Throwable ignored) {
            return null;
        } finally {
            closeQuietly(is);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Throwable ignored) { }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
