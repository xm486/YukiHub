package com.yuki.yukihub.fvp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * FVP 引擎（rfvp）的单游戏启动偏好。
 *
 * <p>字段语义与 Tyranor-Next 的 FVP 引擎设置一致：文本编码、文本 HiDPI、系统字体回退、
 * 自定义字体。rfvp 的存档位置固定为「游戏根目录/save」（rfvp_s###.bin），
 * 引擎侧没有可调项，因此这里只覆盖影响「能不能正常显示」的几项。
 *
 * <p>存储：SharedPreferences（PREF_NAME），键为 "game_&lt;id&gt;"，值为 JSON。
 * 与 PC/ONS 设置同为「引擎侧偏好」，不进数据库、不参与云同步。
 * 注意与游戏删除联动清理（{@link #clear}）。
 */
public final class FvpLaunchPrefs {

    private static final String PREF_NAME = "fvp_launch_prefs";
    private static final String KEY_PREFIX = "game_";

    /** 文本编码默认值（rfvp 未显式传入时自身也是 sjis）。 */
    public static final String NLS_SJIS = "sjis";

    /** 文本编码（对齐 rfvp 支持的三档）。 */
    public static final String[] NLS_LABELS = {
            "Shift-JIS（sjis，日文原版）", "GBK（gbk，简体汉化）", "UTF-8（utf8）"
    };
    public static final String[] NLS_VALUES = {
            NLS_SJIS, "gbk", "utf8"
    };

    /** 文本编码（空/非法归回 sjis）。 */
    public String nls = NLS_SJIS;
    /** 文本 HiDPI 渲染（默认开，中文小字更清晰）。 */
    public boolean textHidpi = true;
    /** 系统 CJK 字体回退（默认开；关闭后缺字游戏会显示豆腐块）。 */
    public boolean systemFont = true;
    /** 自定义字体文件绝对路径（App 私有目录副本）；空 = 不强制。 */
    public String fontPath = "";
    /** 画面放大倍数（等比放大渲染面并居中裁边，字随画面变大）。1.0 = 关闭。 */
    public float screenScale = 1.0f;
    /**
     * 画面铺满：引擎按游戏虚拟分辨率 1:1 渲染，再拉伸铺满整个屏幕。
     * 配合调低 game_mode（如 720P）使用 = 「低分辨率全屏」，字大、无黑边、无裁边。
     */
    public boolean stretchFill = false;
    /**
     * 全局文字缩放（引擎级，需要 YukiHub 补丁版 librfvp.so）。
     * 1.0 = 脚本原大；只放大文字，UI 布局不动、画面无拉伸无裁剪。
     */
    public float textScale = 1.0f;

    /** 画面放大档位。 */
    public static final String[] SCALE_LABELS = {
            "1.0x（默认）", "1.25x", "1.5x", "1.75x", "2.0x"
    };
    public static final float[] SCALE_VALUES = {1.0f, 1.25f, 1.5f, 1.75f, 2.0f};

    /** 文字缩放档位标签（值复用 SCALE_VALUES）。 */
    public static final String[] TEXT_SCALE_LABELS = {
            "1.0x（脚本原大）", "1.25x", "1.5x", "1.75x", "2.0x"
    };

    // ================= 读写 =================

    public static FvpLaunchPrefs load(Context context, long gameId) {
        FvpLaunchPrefs p = new FvpLaunchPrefs();
        if (context == null || gameId <= 0) return p;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String json = sp.getString(KEY_PREFIX + gameId, null);
            if (json == null || json.trim().isEmpty()) return p;
            JSONObject o = new JSONObject(json);
            p.nls = normalizeNls(o.optString("nls", NLS_SJIS));
            p.textHidpi = o.optBoolean("text_hidpi", true);
            p.systemFont = o.optBoolean("system_font", true);
            p.fontPath = normalizePath(o.optString("font_path", ""));
            p.screenScale = normalizeScale(o.optString("screen_scale", "1.0"));
            p.stretchFill = o.optBoolean("stretch_fill", false);
            p.textScale = normalizeScale(o.optString("text_scale", "1.0"));
        } catch (Throwable ignored) { }
        return p;
    }

    public void save(Context context, long gameId) {
        if (context == null || gameId <= 0) return;
        try {
            JSONObject o = new JSONObject();
            o.put("nls", normalizeNls(nls));
            o.put("text_hidpi", textHidpi);
            o.put("system_font", systemFont);
            o.put("font_path", normalizePath(fontPath));
            o.put("screen_scale", String.valueOf(normalizeScale(String.valueOf(screenScale))));
            o.put("stretch_fill", stretchFill);
            o.put("text_scale", String.valueOf(normalizeScale(String.valueOf(textScale))));
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PREFIX + gameId, o.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** 游戏删除时顺带清理。 */
    public static void clear(Context context, long gameId) {
        if (context == null || gameId <= 0) return;
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().remove(KEY_PREFIX + gameId).apply();
        } catch (Throwable ignored) { }
    }

    /** 文本编码归一：白名单外一律回 sjis（rfvp 的默认值，绝不会因非法值启动失败）。 */
    public static String normalizeNls(String value) {
        if (value == null) return NLS_SJIS;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (String a : NLS_VALUES) {
            if (a.equals(v)) return a;
        }
        return NLS_SJIS;
    }

    /** 字体路径归一：只保留非空字符串（文件存在性由启动前的调用方校验）。 */
    public static String normalizePath(String value) {
        if (value == null) return "";
        return value.trim();
    }

    /** 画面放大倍数归一：只接受预设档位，非法归回 1.0。 */
    public static float normalizeScale(String value) {
        if (value == null) return 1.0f;
        try {
            float v = Float.parseFloat(value.trim());
            for (float a : SCALE_VALUES) {
                if (Math.abs(a - v) < 0.01f) return a;
            }
        } catch (NumberFormatException ignored) { }
        return 1.0f;
    }

    /** 值 → 标签（UI 回显用）。 */
    public static String labelOfScale(float value) {
        float v = normalizeScale(String.valueOf(value));
        for (int i = 0; i < SCALE_VALUES.length; i++) {
            if (SCALE_VALUES[i] == v) return SCALE_LABELS[i];
        }
        return SCALE_LABELS[0];
    }

    /** 标签 → 值（UI 保存用）。 */
    public static float valueOfScaleLabel(String label) {
        if (label == null) return 1.0f;
        for (int i = 0; i < SCALE_LABELS.length; i++) {
            if (SCALE_LABELS[i].equals(label)) return SCALE_VALUES[i];
        }
        return 1.0f;
    }

    /** 文字缩放：值 → 标签（UI 回显用）。 */
    public static String labelOfTextScale(float value) {
        float v = normalizeScale(String.valueOf(value));
        for (int i = 0; i < SCALE_VALUES.length; i++) {
            if (SCALE_VALUES[i] == v) return TEXT_SCALE_LABELS[i];
        }
        return TEXT_SCALE_LABELS[0];
    }

    /** 文字缩放：标签 → 值（UI 保存用）。 */
    public static float valueOfTextScaleLabel(String label) {
        if (label == null) return 1.0f;
        for (int i = 0; i < TEXT_SCALE_LABELS.length; i++) {
            if (TEXT_SCALE_LABELS[i].equals(label)) return SCALE_VALUES[i];
        }
        return 1.0f;
    }

    /** 值 → 标签（UI 回显用）。 */
    public static String labelOfNls(String value) {
        String v = normalizeNls(value);
        for (int i = 0; i < NLS_VALUES.length; i++) {
            if (NLS_VALUES[i].equals(v)) return NLS_LABELS[i];
        }
        return NLS_LABELS[0];
    }

    /** 标签 → 值（UI 保存用）。 */
    public static String valueOfNlsLabel(String label) {
        if (label == null) return NLS_SJIS;
        for (int i = 0; i < NLS_LABELS.length; i++) {
            if (NLS_LABELS[i].equals(label)) return NLS_VALUES[i];
        }
        return NLS_SJIS;
    }
}