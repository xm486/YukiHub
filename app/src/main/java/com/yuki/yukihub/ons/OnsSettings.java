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
