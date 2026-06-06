package com.yuki.yukihub.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateChecker {

    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_CHECK_UPDATE_ON_STARTUP = "check_update_on_startup";
    private static final String KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at";
    private static final long AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final String API_URL = "https://api.github.com/repos/xm486/YukiHub/releases/latest";
    private static final String REPO_URL = "https://github.com/xm486/YukiHub";

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public UpdateChecker(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static class UpdateInfo {
        public String tagName;
        public String version;
        public String name;
        public String body;
        public String releaseUrl;
        public String downloadUrl;
        public String apkUrl;
    }

    public interface UpdateCallback {
        void onUpdateAvailable(UpdateInfo info, String currentVersion);
        void onUpToDate(String currentVersion);
        void onError(String message);
    }

    public void checkOnStartupIfNeeded(UpdateCallback callback) {
        try {
            if (!prefs.getBoolean(KEY_CHECK_UPDATE_ON_STARTUP, true)) return;
            long last = prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L);
            if (last > 0 && System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return;
            checkUpdate(false, callback);
        } catch (Throwable t) {
            Log.w("YukiHub", "startup update check skipped", t);
        }
    }

    public void checkUpdateManually(UpdateCallback callback) {
        checkUpdate(true, callback);
    }

    public void checkUpdate(boolean manual, UpdateCallback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                UpdateInfo info = fetchLatestRelease();
                if (info != null) {
                    prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, System.currentTimeMillis()).apply();
                }
                String current = getCurrentVersionName();
                boolean newer = info != null && isNewerVersion(info.version, current);
                if (newer) {
                    uiHandler.post(() -> callback.onUpdateAvailable(info, current));
                } else if (manual) {
                    String v = emptyText(current, "未知");
                    uiHandler.post(() -> callback.onUpToDate(v));
                }
            } catch (Throwable t) {
                Log.w("YukiHub", "check update failed", t);
                if (manual) {
                    String msg = emptyText(t.getMessage(), "请稍后重试");
                    uiHandler.post(() -> callback.onError("检查更新失败：" + msg));
                }
            }
        });
    }

    public UpdateInfo fetchLatestRelease() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_URL).openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "YukiHub-Android/" + getCurrentVersionName());
        try {
            int code = c.getResponseCode();
            String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new RuntimeException("GitHub HTTP " + code + ": " + trimForDialog(text, 160));
            JSONObject o = new JSONObject(text == null ? "{}" : text);
            UpdateInfo info = new UpdateInfo();
            info.tagName = o.optString("tag_name", "");
            info.version = normalizeVersion(info.tagName);
            info.name = o.optString("name", info.tagName);
            info.body = o.optString("body", "");
            info.releaseUrl = o.optString("html_url", REPO_URL + "/releases");
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String assetName = a.optString("name", "");
                    String url = a.optString("browser_download_url", "");
                    if (url == null || url.trim().isEmpty()) continue;
                    if (info.downloadUrl == null || info.downloadUrl.isEmpty()) info.downloadUrl = url;
                    String lowerName = assetName.toLowerCase(Locale.ROOT);
                    String lowerUrl = url.toLowerCase(Locale.ROOT);
                    if (lowerName.endsWith(".apk") || lowerUrl.contains(".apk")) {
                        info.apkUrl = url;
                        break;
                    }
                }
            }
            if (info.version == null || info.version.isEmpty()) info.version = normalizeVersion(info.name);
            if (info.downloadUrl == null || info.downloadUrl.isEmpty()) info.downloadUrl = info.releaseUrl;
            if (info.apkUrl == null || info.apkUrl.isEmpty()) info.apkUrl = info.releaseUrl;
            return info;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    public String getCurrentVersionName() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean isNewerVersion(String latest, String current) {
        String l = normalizeVersionStatic(latest);
        String c = normalizeVersionStatic(current);
        if (l.isEmpty() || c.isEmpty()) return !l.equals(c);
        String[] la = l.split("\\.");
        String[] ca = c.split("\\.");
        int n = Math.max(la.length, ca.length);
        for (int i = 0; i < n; i++) {
            long lv = i < la.length ? parseVersionPart(la[i]) : 0L;
            long cv = i < ca.length ? parseVersionPart(ca[i]) : 0L;
            if (lv > cv) return true;
            if (lv < cv) return false;
        }
        return false;
    }

    private static long parseVersionPart(String part) {
        try {
            if (part == null) return 0L;
            String digits = part.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0L : Long.parseLong(digits);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String normalizeVersionStatic(String value) {
        if (value == null) return "";
        String v = value.trim();
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+){1,5})").matcher(v);
        if (m.find()) return m.group(1);
        v = v.replaceFirst("^[vV]", "").replaceAll("[^0-9.]", "");
        while (v.startsWith(".")) v = v.substring(1);
        while (v.endsWith(".")) v = v.substring(0, v.length() - 1);
        return v;
    }

    public static String normalizeVersion(String value) {
        return normalizeVersionStatic(value);
    }

    public static String trimForDialog(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        if (max <= 0 || t.length() <= max) return t;
        return t.substring(0, max) + "\n...";
    }

    public static String emptyText(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s;
    }

    public String getRepoUrl() {
        return REPO_URL;
    }

    private String readSmallText(InputStream is) throws Exception {
        if (is == null) return "";
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0, len;
        while ((len = is.read(buf)) != -1 && total < 256 * 1024) {
            bos.write(buf, 0, len);
            total += len;
        }
        return bos.toString("UTF-8");
    }
}