package com.yuki.yukihub;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Receives yukihub://oauth/callback from KUN OAuth and converts it into a normal YukiHub session.
 * This is an additive third-party quick-login path; the existing email login/register flow is untouched.
 */
public class KungalOAuthCallbackActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String AUTH_BASE_URL = "https://yukihub.zh.kg/api";

    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
    private static final String KEY_AUTH_REFRESH_TOKEN = "auth_refresh_token";
    private static final String KEY_AUTH_USER_ID = "auth_user_id";
    private static final String KEY_AUTH_NICKNAME = "auth_nickname";
    private static final String KEY_AUTH_EMAIL = "auth_email";
    private static final String KEY_AUTH_AVATAR = "auth_avatar";
    private static final String KEY_AUTH_STATUS = "auth_status";
    private static final String KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled";

    private static final String KEY_KUN_OAUTH_STATE = "kun_oauth_state";
    private static final String KEY_KUN_OAUTH_CODE_VERIFIER = "kun_oauth_code_verifier";
    private static final String KEY_KUN_OAUTH_STARTED_AT = "kun_oauth_started_at";
    private static final String KEY_KUN_OAUTH_MODE = "kun_oauth_mode";
    private static final String KEY_KUN_BOUND = "kungal_bound";
    private static final String KUN_OAUTH_MODE_BIND = "bind";

    private SharedPreferences prefs;
    private TextView status;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.yuki.yukihub.util.UiScaleUtil.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(0xFFEAF7FF);
        status.setTextSize(15);
        status.setText("正在完成鲲站快捷登录...");
        status.setBackgroundColor(0xFF0B1020);
        setContentView(status);

        handleCallback(getIntent() == null ? null : getIntent().getData());
    }

    private void handleCallback(Uri uri) {
        if (uri == null) {
            fail("未收到登录回调");
            return;
        }
        String error = uri.getQueryParameter("error");
        if (error != null && !error.trim().isEmpty()) {
            fail("鲲站登录取消或失败：" + error);
            return;
        }
        String code = uri.getQueryParameter("code");
        String state = uri.getQueryParameter("state");
        String expectedState = prefs == null ? "" : prefs.getString(KEY_KUN_OAUTH_STATE, "");
        String verifier = prefs == null ? "" : prefs.getString(KEY_KUN_OAUTH_CODE_VERIFIER, "");
        long startedAt = prefs == null ? 0L : prefs.getLong(KEY_KUN_OAUTH_STARTED_AT, 0L);

        if (code == null || code.trim().isEmpty()) {
            fail("登录回调缺少授权码");
            return;
        }
        if (state == null || expectedState == null || !state.equals(expectedState)) {
            fail("登录状态校验失败，请重新尝试");
            return;
        }
        if (verifier == null || verifier.trim().isEmpty()) {
            fail("PKCE 校验信息已丢失，请重新尝试");
            return;
        }
        if (startedAt <= 0 || System.currentTimeMillis() - startedAt > 10L * 60L * 1000L) {
            fail("授权已超时，请重新登录");
            return;
        }

        new Thread(() -> exchangeCode(code.trim(), verifier.trim())).start();
    }

    private void exchangeCode(String code, String codeVerifier) {
        try {
            JSONObject body = new JSONObject();
            body.put("code", code);
            body.put("codeVerifier", codeVerifier);
            body.put("redirectUri", "yukihub://oauth/callback");

            String mode = prefs == null ? "" : prefs.getString(KEY_KUN_OAUTH_MODE, "");
            if (KUN_OAUTH_MODE_BIND.equals(mode)) {
                String accessToken = prefs == null ? "" : prefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
                JSONObject resp = postJson(AUTH_BASE_URL + "/auth/kungal/bind", body, accessToken);
                JSONObject user = resp.optJSONObject("user");
                if (user != null) {
                    prefs.edit()
                            .putString(KEY_AUTH_EMAIL, firstString(user, "email"))
                            .putBoolean(KEY_KUN_BOUND, true)
                            .apply();
                } else {
                    prefs.edit().putBoolean(KEY_KUN_BOUND, true).apply();
                }
                clearPendingOAuth();
                runOnUiThread(() -> openProfile("鲲站账号绑定成功"));
            } else {
                JSONObject resp = postJson(AUTH_BASE_URL + "/auth/kungal/android_callback", body, null);
                saveSession(resp);
                clearPendingOAuth();
                runOnUiThread(() -> openProfile("鲲站快捷登录成功"));
            }
        } catch (Throwable t) {
            String mode = prefs == null ? "" : prefs.getString(KEY_KUN_OAUTH_MODE, "");
            runOnUiThread(() -> fail((KUN_OAUTH_MODE_BIND.equals(mode) ? "鲲站账号绑定失败：" : "鲲站快捷登录失败：") + readableError(t)));
        }
    }

    private JSONObject postJson(String urlStr, JSONObject body, String bearerToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "YukiHub/1.0 (Android)");
        if (bearerToken != null && !bearerToken.trim().isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearerToken.trim());
        byte[] data = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) { os.write(data); }
        int http = c.getResponseCode();
        String text = readSmallText(http >= 200 && http < 300 ? c.getInputStream() : c.getErrorStream());
        if (text != null && text.trim().startsWith("<")) throw new RuntimeException("服务器返回了 HTML 页面，请稍后重试");
        if (http < 200 || http >= 300) throw new RuntimeException("HTTP " + http + ": " + text);
        return text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private String readSmallText(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
        return bos.toString("UTF-8");
    }

    private void saveSession(JSONObject resp) throws Exception {
        if (resp == null) throw new RuntimeException("empty response");
        String access = firstString(resp, "accessToken", "access_token", "token");
        String refresh = firstString(resp, "refreshToken", "refresh_token");
        JSONObject user = resp.optJSONObject("user");
        if (access == null || access.trim().isEmpty()) throw new RuntimeException("服务器未返回令牌");
        String userId = user == null ? firstString(resp, "userId", "user_id", "id") : firstString(user, "id", "userId", "user_id");
        String nickname = user == null ? firstString(resp, "nickname", "name", "username") : firstString(user, "nickname", "name", "username");
        String email = user == null ? firstString(resp, "email") : firstString(user, "email");
        String avatar = user == null ? firstString(resp, "avatarUrl", "avatar_url", "avatar") : firstString(user, "avatarUrl", "avatar_url", "avatar");

        prefs.edit()
                .putString(KEY_AUTH_ACCESS_TOKEN, access)
                .putString(KEY_AUTH_REFRESH_TOKEN, refresh == null ? "" : refresh)
                .putString(KEY_AUTH_USER_ID, userId == null ? "" : userId)
                .putString(KEY_AUTH_NICKNAME, nickname == null ? "" : nickname)
                .putString(KEY_AUTH_EMAIL, email == null ? "" : email)
                .putString(KEY_AUTH_AVATAR, avatar == null ? "" : avatar)
                .putString(KEY_AUTH_STATUS, "online")
                .putBoolean(KEY_KUN_BOUND, user == null || user.optBoolean("kungalBound", true))
                .putBoolean(KEY_CLOUD_SYNC_ENABLED, false)
                .apply();
    }

    private String firstString(JSONObject o, String... keys) {
        if (o == null || keys == null) return "";
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.trim().isEmpty() && !"null".equalsIgnoreCase(v.trim())) return v.trim();
        }
        return "";
    }

    private void openProfile(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("home_target", "profile");
        startActivity(i);
        finish();
    }

    private void clearPendingOAuth() {
        if (prefs == null) return;
        prefs.edit()
                .remove(KEY_KUN_OAUTH_STATE)
                .remove(KEY_KUN_OAUTH_CODE_VERIFIER)
                .remove(KEY_KUN_OAUTH_STARTED_AT)
                .remove(KEY_KUN_OAUTH_MODE)
                .apply();
    }

    private String readableError(Throwable t) {
        if (t == null || t.getMessage() == null) return "请检查网络或稍后重试";
        String msg = t.getMessage();
        if (msg.startsWith("HTTP ")) {
            int idx = msg.indexOf(": ");
            if (idx > 0) {
                String json = msg.substring(idx + 2);
                try {
                    JSONObject o = new JSONObject(json);
                    String e = o.optString("error", o.optString("message", json));
                    if (e != null && !e.trim().isEmpty()) return e.trim();
                } catch (Throwable ignored) { }
            }
        }
        return msg;
    }

    private void fail(String message) {
        clearPendingOAuth();
        if (status != null) status.setText(message + "\n\n请返回 YukiHub 重新尝试。");
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
