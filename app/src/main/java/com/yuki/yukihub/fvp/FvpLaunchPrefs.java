package com.yuki.yukihub.fvp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;

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
    /** 简体中文编码（GBK 直写型汉化补丁）。 */
    public static final String NLS_GBK = "gbk";

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
/**
     * 行距跟随程度（引擎级；只在文字被放大时起作用）。
     * -1.0 = 比原版行距更紧；0.0 = 与原版行距一致（默认）；1.0 = 行距随字号等比。
     * 字形大小不受影响。
     */
    public float lineScale = 0.0f;
    /** 是否已经跑过一次自动编码探测（true 后不再自动改，尊重用户手选）。 */
    public boolean nlsAuto = false;

    /** 画面放大档位。 */
    public static final String[] SCALE_LABELS = {
            "1.0x（默认）", "1.25x", "1.5x", "1.75x", "2.0x"
    };
    public static final float[] SCALE_VALUES = {1.0f, 1.25f, 1.5f, 1.75f, 2.0f};

    /** 文字缩放档位标签（值复用 SCALE_VALUES）。 */
    public static final String[] TEXT_SCALE_LABELS = {
            "1.0x（脚本原大）", "1.25x", "1.5x", "1.75x", "2.0x"
    };

    /**
     * 行距档位标签：字号放大时，行与行之间的距离跟随多少。
     * 0.0 = 行距完全不变（最紧凑）；0.5 = 放大增量减半（默认）；1.0 = 与字号等比。
     */
    public static final String[] LINE_SCALE_LABELS = {
            "比原版更紧（-1.0，可能贴字）", "比原版略紧（-0.5）", "与原版行距一致（0.0，推荐）",
            "比原版松一半（+0.5）", "随字号等比（+1.0，原样）"
    };
    public static final float[] LINE_SCALE_VALUES = {-1.0f, -0.5f, 0.0f, 0.5f, 1.0f};

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
            p.lineScale = normalizeLineScale(o.optString("line_scale", "0.0"));
            p.nlsAuto = o.optBoolean("nls_auto", false);
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
            o.put("line_scale", String.valueOf(normalizeLineScale(String.valueOf(lineScale))));
            o.put("nls_auto", nlsAuto);
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

    /**
     * load 的带路径提示版本：从未探测过编码时（nls_auto=false），采样脚本推断
     * SJIS/GBK 并落盘。只在「检出 GBK 且当前是 SJIS」时纠偏，之后永不覆盖手选。
     */
    public static FvpLaunchPrefs load(Context context, long gameId, String rootHint) {
        FvpLaunchPrefs p = load(context, gameId);
        if (context == null || gameId <= 0 || p.nlsAuto) return p;
        try {
            String detected = detectNlsFromRoot(rootHint);
            if (detected != null && NLS_GBK.equals(detected) && !NLS_GBK.equals(p.nls)) {
                android.util.Log.i("FvpLaunchPrefs",
                        "nls auto-detect: gbk (was " + p.nls + ") game=" + gameId);
                p.nls = NLS_GBK;
            }
            p.nlsAuto = true;
            p.save(context, gameId);
        } catch (Throwable ignored) { }
        return p;
    }

    /**
     * 采样 hcb/bch 脚本推断文本编码。
     * 原理：汉化组「GBK 直写」的脚本里有大量 SJIS 解不开、但 GBK 能解开的 pushstring；
     * 日文原版几乎不存在这种串。判据：GBK-only 串 ≥30 且占比 ≥5% → GBK。
     * 任何异常都返回 null（保持默认 sjis，绝不因探测导致启动异常）。
     */
    public static String detectNlsFromRoot(String rootUriOrPath) {
        try {
            String root = rootUriOrPath == null ? "" : rootUriOrPath.trim();
            if (root.startsWith("file://")) {
                root = android.net.Uri.decode(root.substring(7));
            }
            if (!root.startsWith("/")) return null;
            File dir = new File(root);
            File[] children = dir.listFiles();
            if (children == null) return null;
            // 与 rfvp find_hcb 同规则：优先 *.bch（汉化补丁脚本），否则 *.hcb，取字典序第一个
            File bch = null, hcb = null;
            for (File f : children) {
                if (!f.isFile()) continue;
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.endsWith(".bch")) {
                    if (bch == null || n.compareTo(bch.getName().toLowerCase(java.util.Locale.ROOT)) < 0) bch = f;
                } else if (n.endsWith(".hcb")) {
                    if (hcb == null || n.compareTo(hcb.getName().toLowerCase(java.util.Locale.ROOT)) < 0) hcb = f;
                }
            }
            File script = bch != null ? bch : hcb;
            return script == null ? null : detectNlsFromFile(script);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String detectNlsFromFile(File script) {
        try {
            long size = script.length();
            int cap = (int) Math.min(size, 8L * 1024 * 1024);
            if (cap < 4096) return null;
            byte[] buf = new byte[cap];
            java.io.FileInputStream in = new java.io.FileInputStream(script);
            int read = 0;
            while (read < cap) {
                int r = in.read(buf, read, cap - read);
                if (r < 0) break;
                read += r;
            }
            in.close();
            java.nio.charset.Charset sjis = java.nio.charset.Charset.forName("Shift_JIS");
            java.nio.charset.Charset gbk = java.nio.charset.Charset.forName("GBK");
            int total = 0, sjisOk = 0, gbkOnly = 0;
            int i = 0;
            while (i < read - 2 && total < 40000) {
                if (buf[i] == 0x0E) {
                    int len = buf[i + 1] & 0xFF;
                    if (len >= 2 && len <= 120 && i + 2 + len <= read) {
                        total++;
                        byte[] s = new byte[len];
                        System.arraycopy(buf, i + 2, s, 0, len);
                        if (strictDecode(s, sjis)) {
                            sjisOk++;
                        } else if (strictDecode(s, gbk)) {
                            gbkOnly++;
                        }
                        i += 2 + len;
                        continue;
                    }
                }
                i++;
            }
            android.util.Log.i("FvpLaunchPrefs", "nls detect: file=" + script.getName()
                    + " total=" + total + " sjisOk=" + sjisOk + " gbkOnly=" + gbkOnly);
            if (gbkOnly >= 30 && total > 0 && gbkOnly * 20 >= total) {
                return NLS_GBK;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean strictDecode(byte[] bytes, java.nio.charset.Charset cs) {
        try {
            java.nio.charset.CodingErrorAction rep = java.nio.charset.CodingErrorAction.REPORT;
            cs.newDecoder().onMalformedInput(rep).onUnmappableCharacter(rep)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Throwable t) {
            return false;
        }
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

    /** 行距档位：值 → 标签。 */
    public static String labelOfLineScale(float value) {
        float v = normalizeLineScale(String.valueOf(value));
        for (int i = 0; i < LINE_SCALE_VALUES.length; i++) {
            if (Math.abs(LINE_SCALE_VALUES[i] - v) < 0.001f) return LINE_SCALE_LABELS[i];
        }
        return LINE_SCALE_LABELS[2];
    }

    /** 行距档位：标签 → 值（UI 保存用）。 */
    public static float valueOfLineScaleLabel(String label) {
        if (label == null) return 0.0f;
        for (int i = 0; i < LINE_SCALE_LABELS.length; i++) {
            if (LINE_SCALE_LABELS[i].equals(label)) return LINE_SCALE_VALUES[i];
        }
        return 0.0f;
    }

    /** 行距系数合法范围 -1.0–1.0，非法归 0.0（= 与原版行距一致）。 */
    public static float normalizeLineScale(String raw) {
        try {
            float v = Float.parseFloat(raw);
            if (Float.isNaN(v) || Float.isInfinite(v)) return 0.0f;
            return Math.max(-1.0f, Math.min(1.0f, v));
        } catch (Throwable ignored) {
            return 0.0f;
        }
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