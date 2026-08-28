package com.yuki.yukihub.ons;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

public final class OnsSettings {
    public static final String PREF_NAME = "onsyuri";
    public static final String EXTRA_GAME_ARGS = "gameargs";
    public static final String EXTRA_GAME_URI = "gameuri";
    public static final String EXTRA_IGNORE_CUTOUT = "ignorecutout";

    private static final String TAG = "OnsSettings";
    private static final String KEY_ENCODING_MIGRATED_GBK = "encoding_migrated_gbk_v2";

    public boolean stretchFull = false;
    public boolean ignoreCutout = true;
    public boolean sharpness = false;
    public String sharpnessValue = "2";
    public boolean disableVideo = false;
    // Tyranor / OnsYuri 对中文 ONS 更友好，默认按原 TY 行为走 GBK。
    public String encoding = "gbk";
    public boolean scopedSaveDir = true;
    public boolean allowEditArgs = true;
    // ===== 以下为对齐 onsyuri 0.7.7 补全的参数 =====
    /** --no-vsync：关闭垂直同步。部分机型上开着会掉帧，关掉更流畅但可能撕裂。 */
    public boolean noVsync = false;
    /** --fontcache：缓存默认字体，长文本渲染更快，代价是多占一点内存。 */
    public boolean fontCache = false;
    /** --render-font-outline：描边渲染文字，替代默认的投影效果。 */
    public boolean renderFontOutline = false;
    /** --disable-rescale：不缩放档案内图片，保持像素原样。 */
    public boolean disableRescale = false;
    /** --force-button-shortcut：忽略脚本的 useescspc / getenter，强制启用按键快捷操作。 */
    public boolean forceButtonShortcut = false;
    /** --enable-wheeldown-advance：滚轮下滚推进文本。 */
    public boolean wheelDownAdvance = false;
    /** --debug:1：输出引擎调试日志，排查脚本问题时才需要开。 */
    public boolean debugLog = false;
    /** 自定义分辨率，0 表示不指定（--width / --height）。 */
    public int forceWidth = 0;
    public int forceHeight = 0;

    public static OnsSettings load(Context context) {
        OnsSettings settings = defaults();
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String json = sp.getString(EXTRA_GAME_ARGS, null);
            if (json != null && !json.trim().isEmpty()) settings.readJson(new JSONObject(json));
            if (!sp.getBoolean(KEY_ENCODING_MIGRATED_GBK, false) && "sjis".equals(settings.encoding)) {
                settings.encoding = "gbk";
                sp.edit().putBoolean(KEY_ENCODING_MIGRATED_GBK, true).putString(EXTRA_GAME_ARGS, settings.toJson().toString()).apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "load failed", t);
        }
        return settings;
    }

    public void save(Context context) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(EXTRA_GAME_ARGS, toJson().toString())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "save failed", t);
        }
    }

    public static OnsSettings defaults() {
        return new OnsSettings();
    }

    private void readJson(JSONObject o) throws JSONException {
        stretchFull = o.optBoolean("strechfull", stretchFull);
        ignoreCutout = o.optBoolean("ignorecutout", ignoreCutout);
        sharpness = o.optBoolean("sharpness", sharpness);
        sharpnessValue = o.optString("sharpness_value", sharpnessValue);
        disableVideo = o.optBoolean("disablevideo", disableVideo);
        encoding = normalizeEncoding(o.optString("encoding", encoding));
        scopedSaveDir = o.optBoolean("scopedsavedir", scopedSaveDir);
        allowEditArgs = o.optBoolean("alloweditargs", allowEditArgs);
        noVsync = o.optBoolean("novsync", noVsync);
        fontCache = o.optBoolean("fontcache", fontCache);
        renderFontOutline = o.optBoolean("renderfontoutline", renderFontOutline);
        disableRescale = o.optBoolean("disablerescale", disableRescale);
        forceButtonShortcut = o.optBoolean("forcebuttonshortcut", forceButtonShortcut);
        wheelDownAdvance = o.optBoolean("wheeldownadvance", wheelDownAdvance);
        debugLog = o.optBoolean("debuglog", debugLog);
        forceWidth = o.optInt("width", forceWidth);
        forceHeight = o.optInt("height", forceHeight);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        // 保持 Tyranor / OnsYuri 原字段名，strechfull 是原项目拼写。
        o.put("strechfull", stretchFull);
        o.put("ignorecutout", ignoreCutout);
        o.put("sharpness", sharpness);
        o.put("sharpness_value", safeSharpness());
        o.put("disablevideo", disableVideo);
        o.put("encoding", normalizeEncoding(encoding));
        o.put("scopedsavedir", scopedSaveDir);
        o.put("alloweditargs", allowEditArgs);
        o.put("novsync", noVsync);
        o.put("fontcache", fontCache);
        o.put("renderfontoutline", renderFontOutline);
        o.put("disablerescale", disableRescale);
        o.put("forcebuttonshortcut", forceButtonShortcut);
        o.put("wheeldownadvance", wheelDownAdvance);
        o.put("debuglog", debugLog);
        o.put("width", forceWidth);
        o.put("height", forceHeight);
        return o;
    }

    public ArrayList<String> buildArgs(Context context, String gameDir) {
        String root = gameDir == null ? "" : gameDir;
        ArrayList<String> args = new ArrayList<>();
        args.add("--root");
        args.add(root);
        args.add("--font");
        args.add(root.endsWith("/") ? root + "default.ttf" : root + "/default.ttf");
        args.add(stretchFull ? "--fullscreen2" : "--fullscreen");
        if (disableVideo) args.add("--no-video");
        args.add("--enc:" + normalizeEncoding(encoding));
        if (scopedSaveDir) {
            File base = context.getExternalFilesDir(null);
            if (base != null) {
                String saveName = guessName(root);
                File saveDir = new File(new File(base, "save"), saveName);
                if (saveDir.exists() || saveDir.mkdirs()) {
                    args.add("--save-dir");
                    args.add(saveDir.getAbsolutePath());
                }
            }
        }
        if (sharpness) {
            args.add("--sharpness");
            args.add(safeSharpness());
        }
        // ===== onsyuri 0.7.7 支持的其余开关 =====
        if (noVsync) args.add("--no-vsync");
        if (fontCache) args.add("--fontcache");
        if (renderFontOutline) args.add("--render-font-outline");
        if (disableRescale) args.add("--disable-rescale");
        if (forceButtonShortcut) args.add("--force-button-shortcut");
        if (wheelDownAdvance) args.add("--enable-wheeldown-advance");
        if (debugLog) args.add("--debug:1");
        // 宽高必须成对给出，只给一个会让引擎按默认值算另一边，反而更容易出错。
        if (forceWidth > 0 && forceHeight > 0) {
            args.add("--width");
            args.add(String.valueOf(forceWidth));
            args.add("--height");
            args.add(String.valueOf(forceHeight));
        }
        return args;
    }

    private String safeSharpness() {
        String v = sharpnessValue == null ? "2" : sharpnessValue.trim();
        return v.isEmpty() ? "2" : v;
    }

    public static String normalizeEncoding(String value) {
        String v = value == null ? "gbk" : value.trim().toLowerCase(Locale.ROOT);
        if ("gbk".equals(v) || "utf8".equals(v) || "sjis".equals(v)) return v;
        if ("utf-8".equals(v)) return "utf8";
        if ("shift-jis".equals(v) || "shift_jis".equals(v)) return "sjis";
        return "gbk";
    }

    private static String guessName(String path) {
        if (path == null || path.isEmpty()) return "ONSGame";
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        return slash >= 0 && slash + 1 < p.length() ? p.substring(slash + 1) : p;
    }
}
