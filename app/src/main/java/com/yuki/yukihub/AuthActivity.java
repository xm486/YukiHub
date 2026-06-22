package com.yuki.yukihub;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import android.graphics.drawable.GradientDrawable;

import androidx.appcompat.app.AppCompatActivity;

import com.yuki.yukihub.ui.DynamicTheme;
import com.yuki.yukihub.ui.ThemeColorExtractor;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
public class AuthActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.yuki.yukihub.util.UiScaleUtil.wrap(newBase));
    }



    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String AUTH_BASE_URL = "https://yukihub.kesug.com/api";
    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
    private static final String KEY_AUTH_REFRESH_TOKEN = "auth_refresh_token";
    private static final String KEY_AUTH_USER_ID = "auth_user_id";
    private static final String KEY_AUTH_NICKNAME = "auth_nickname";
    private static final String KEY_AUTH_EMAIL = "auth_email";
    private static final String KEY_AUTH_AVATAR = "auth_avatar";
    private static final String KEY_AUTH_STATUS = "auth_status";
    private static final String KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled";
    private static final String KEY_PROFILE_NAME = "profile_name";
    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.58 Mobile Safari/537.36";

    private boolean registerMode = false;
private SharedPreferences prefs;

private TextView tabLogin, tabRegister, tvFormTitle, tvFormHint, tvAuthStatus;
private LinearLayout rowNickname, rowConfirmPassword;
private EditText etNickname, etEmail, etPassword, etConfirmPassword;
private Button btnSubmit;
private TextView tvContinueLocal;

    @Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    
    // Remove title bar completely
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    
    setContentView(R.layout.activity_auth);

    prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    enterImmersiveMode();

    // Set dialog size - taller
    if (getWindow() != null) {
        getWindow().setLayout(
            (int) (getResources().getDisplayMetrics().widthPixels * 0.45f),
            (int) (getResources().getDisplayMetrics().heightPixels * 0.85f)
        );
    }

    initViews();
    setupListeners();
    applyDynamicThemeToAuth();
    switchToLogin();
}

    private void initViews() {
    tabLogin = findViewById(R.id.tabLogin);
    tabRegister = findViewById(R.id.tabRegister);
    tvFormTitle = findViewById(R.id.tvFormTitle);
    tvFormHint = findViewById(R.id.tvFormHint);
    tvAuthStatus = findViewById(R.id.tvAuthStatus);

    rowNickname = findViewById(R.id.rowNickname);
    rowConfirmPassword = findViewById(R.id.rowConfirmPassword);

    etNickname = findViewById(R.id.etNickname);
    etEmail = findViewById(R.id.etEmail);
    etPassword = findViewById(R.id.etPassword);
    etConfirmPassword = findViewById(R.id.etConfirmPassword);

    btnSubmit = findViewById(R.id.btnSubmit);
    tvContinueLocal = findViewById(R.id.tvContinueLocal);
}

    private void setupListeners() {
    tabLogin.setOnClickListener(v -> switchToLogin());
    tabRegister.setOnClickListener(v -> switchToRegister());

    btnSubmit.setOnClickListener(v -> onSubmit());

    // 长按标题测试API连接
    tvFormTitle.setOnLongClickListener(v -> {
        testApiConnection();
        return true;
    });

    tvContinueLocal.setOnClickListener(v -> finish());
}

    private void switchToLogin() {
    registerMode = false;
    DynamicTheme dt = DynamicTheme.getInstance();
    boolean themed = dt.isEnabled() && dt.getColors() != null;
    tabLogin.setTextColor(themed ? 0xFFF0F4FA : 0xFFEAF7FF);
    tabLogin.setBackgroundResource(R.drawable.bg_auth_tab_active);
    tabRegister.setTextColor(themed ? 0xFFB0B8C8 : 0xFF4A5568);
    tabRegister.setBackgroundResource(R.drawable.bg_auth_tab_inactive);

    tvFormTitle.setText("欢迎回来");
    tvFormHint.setText("登录后开启云同步和跨设备恢复");
    btnSubmit.setText("登录");

    rowNickname.setVisibility(View.GONE);
    rowConfirmPassword.setVisibility(View.GONE);

    tvAuthStatus.setVisibility(View.GONE);
}

private void switchToRegister() {
    registerMode = true;
    DynamicTheme dt = DynamicTheme.getInstance();
    boolean themed = dt.isEnabled() && dt.getColors() != null;
    tabRegister.setTextColor(themed ? 0xFFF0F4FA : 0xFFEAF7FF);
    tabRegister.setBackgroundResource(R.drawable.bg_auth_tab_active);
    tabLogin.setTextColor(themed ? 0xFFB0B8C8 : 0xFF4A5568);
    tabLogin.setBackgroundResource(R.drawable.bg_auth_tab_inactive);

    tvFormTitle.setText("创建账户");
    tvFormHint.setText("注册后开启云同步、好友聊天和跨设备恢复");
    btnSubmit.setText("创建账户");

    rowNickname.setVisibility(View.VISIBLE);
    rowConfirmPassword.setVisibility(View.VISIBLE);

    tvAuthStatus.setVisibility(View.GONE);
}

    private void onSubmit() {
String email = etEmail.getText() == null ? "" : etEmail.getText().toString().trim();
String password = etPassword.getText() == null ? "" : etPassword.getText().toString();
String nickname = etNickname.getText() == null ? "" : etNickname.getText().toString().trim();
String confirmPassword = etConfirmPassword.getText() == null ? "" : etConfirmPassword.getText().toString();

if (!isValidEmail(email)) {
showStatus("请输入有效的邮箱地址", 0xFFFF9500);
return;
}
if (password.length() < 6) {
showStatus("密码至少需要6位", 0xFFFF9500);
return;
}
if (registerMode) {
if (nickname.length() < 2 || nickname.length() > 20) {
showStatus("昵称需要2-20个字符", 0xFFFF9500);
return;
}
if (nickname.matches(".*[<>{}\\[\\]\\\\/].*")) {
showStatus("昵称不能包含特殊字符", 0xFFFF9500);
return;
}
if (!password.equals(confirmPassword)) {
showStatus("两次密码输入不一致", 0xFFFF9500);
return;
}
}

btnSubmit.setEnabled(false);
btnSubmit.setText(registerMode ? "注册中..." : "登录中...");
showStatus("正在连接...", 0xFF8E9AB5);

performAuth(email, password, nickname);
}

private boolean isValidEmail(String email) {
if (email == null) return false;
return android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches();
}

/**
 * 测试API连接
 */
private void testApiConnection() {
    showStatus("正在测试API...", 0xFF8E9AB5);
    
    new Thread(() -> {
        try {
            String testUrl = AUTH_BASE_URL + "/health";
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(testUrl).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "YukiHub/1.0 (Android)");
            
            int code = c.getResponseCode();
            String text = "";
            java.io.InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            if (is != null) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                text = bos.toString("UTF-8");
            }
            
            final String result = "HTTP " + code + "\n" + text;
            runOnUiThread(() -> showStatus(result, code >= 200 && code < 300 ? 0xFF34D158 : 0xFFFF3B30));
        } catch (Throwable t) {
            runOnUiThread(() -> showStatus("连接失败: " + t.getMessage(), 0xFFFF3B30));
        }
    }).start();
}

    private void performAuth(String email, String password, String nickname) {
        new Thread(() -> {
            try {
                JSONObject resp;
                
                // 注册和登录都用 GET 方式（绕过 InfinityFree 的 POST 拦截）
                String endpoint = registerMode ? "/auth/register" : "/auth/login";
                String params = "email=" + java.net.URLEncoder.encode(email, "UTF-8")
                        + "&password=" + java.net.URLEncoder.encode(password, "UTF-8");
                if (registerMode) {
                    params += "&nickname=" + java.net.URLEncoder.encode(nickname, "UTF-8");
                }
                String url = AUTH_BASE_URL + endpoint + "?" + params;
                resp = getJson(url);
                
                saveSession(resp, email, nickname);

                runOnUiThread(() -> {
                    Toast.makeText(this, registerMode ? "注册成功" : "登录成功", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Throwable t) {
                Log.w("YukiHub", "Auth failed", t);
                runOnUiThread(() -> {
                    btnSubmit.setEnabled(true);
                    btnSubmit.setText(registerMode ? "创建账户" : "登录");
                    showStatus("连接失败：" + (t.getMessage() != null ? t.getMessage() : "请检查网络"), 0xFFFF3B30);
                });
            }
        }).start();
    }

    private JSONObject getJson(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(12000);
        c.setReadTimeout(18000);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        c.setRequestProperty("User-Agent", BROWSER_UA);
        c.setRequestProperty("Referer", "https://yukihub.kesug.com/");
        int code = c.getResponseCode();
        String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (text != null && text.trim().startsWith("<")) {
            throw new RuntimeException("服务器返回了HTML页面，可能是免费主机防护页/缓存页，请稍后重试");
        }
        if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + ": " + text);
        return text != null && !text.trim().isEmpty() ? new JSONObject(text) : new JSONObject();
    }

    private void saveSession(JSONObject resp, String emailFallback, String nicknameFallback) throws Exception {
        if (resp == null) throw new RuntimeException("empty response");
        String access = firstString(resp, "accessToken", "access_token", "token");
        String refresh = firstString(resp, "refreshToken", "refresh_token");
        JSONObject user = resp.optJSONObject("user");
        String userId = user != null ? firstString(user, "id", "userId", "user_id") : firstString(resp, "userId", "user_id", "id");
        String nickname = user != null ? firstString(user, "nickname", "name", "username") : firstString(resp, "nickname", "name", "username");
        String email = user != null ? firstString(user, "email") : firstString(resp, "email");
        String avatar = user != null ? firstString(user, "avatarUrl", "avatar_url", "avatar") : firstString(resp, "avatarUrl", "avatar_url", "avatar");

        if (access == null || access.isEmpty()) throw new RuntimeException("服务器未返回令牌");
        if (nickname == null || nickname.isEmpty()) nickname = nicknameFallback;
        if (email == null || email.isEmpty()) email = emailFallback;

        prefs.edit()
                .putString(KEY_AUTH_ACCESS_TOKEN, access)
                .putString(KEY_AUTH_REFRESH_TOKEN, refresh != null ? refresh : "")
                .putString(KEY_AUTH_USER_ID, userId != null ? userId : "")
                .putString(KEY_AUTH_NICKNAME, nickname)
                .putString(KEY_AUTH_EMAIL, email)
                .putString(KEY_AUTH_AVATAR, avatar != null ? avatar : "")
                .putString(KEY_AUTH_STATUS, "online")
                .putBoolean(KEY_CLOUD_SYNC_ENABLED, true)
                .apply();
    }

    private JSONObject postJson(String urlStr, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(12000);
        c.setReadTimeout(18000);
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = body != null ? body.toString().getBytes(StandardCharsets.UTF_8) : new byte[0];
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) { os.write(data); }
        int code = c.getResponseCode();
        String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + ": " + text);
        return text != null && !text.trim().isEmpty() ? new JSONObject(text) : new JSONObject();
    }

    private String readSmallText(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
        return bos.toString("UTF-8");
    }

    private String firstString(JSONObject o, String... keys) {
        if (o == null || keys == null) return "";
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.trim().isEmpty() && !"null".equalsIgnoreCase(v.trim())) return v.trim();
        }
        return "";
    }

    private void showStatus(String msg, int color) {
        tvAuthStatus.setVisibility(View.VISIBLE);
        tvAuthStatus.setText(msg);
        tvAuthStatus.setTextColor(color);
    }

    private void applyDynamicThemeToAuth() {
        DynamicTheme dt = DynamicTheme.getInstance();
        if (!dt.isEnabled() || dt.getColors() == null) return;
        ThemeColorExtractor.ThemeColors colors = dt.getColors();
        // Tint all text views to white/gray-white
        int textColor = 0xFFF0F4FA;
        int mutedColor = 0xFFB0B8C8;
        int hintColor = 0xFF8090A8;
        tvFormTitle.setTextColor(textColor);
        tvFormHint.setTextColor(mutedColor);
        tvAuthStatus.setTextColor(mutedColor);
        tabLogin.setTextColor(textColor);
        tabRegister.setTextColor(mutedColor);
        etNickname.setTextColor(textColor);
        etNickname.setHintTextColor(hintColor);
        etEmail.setTextColor(textColor);
        etEmail.setHintTextColor(hintColor);
        etPassword.setTextColor(textColor);
        etPassword.setHintTextColor(hintColor);
        etConfirmPassword.setTextColor(textColor);
        etConfirmPassword.setHintTextColor(hintColor);
        btnSubmit.setTextColor(0xFFFFFFFF);
        tvContinueLocal.setTextColor(mutedColor);
        // Tint backgrounds
        btnSubmit.setBackground(tintAuthButton(colors));
        etNickname.setBackground(tintAuthInput(colors));
        etEmail.setBackground(tintAuthInput(colors));
        etPassword.setBackground(tintAuthInput(colors));
        etConfirmPassword.setBackground(tintAuthInput(colors));
        // Dialog background
        View root = findViewById(android.R.id.content);
        if (root != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor((0xF0 << 24) | (colors.card & 0x00FFFFFF));
            bg.setStroke(dp(1), (0x5E << 24) | (colors.primary & 0x00FFFFFF));
            bg.setCornerRadius(dp(10));
            root.setBackground(bg);
        }
    }

    private GradientDrawable tintAuthButton(ThemeColorExtractor.ThemeColors c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(0xFF << 24) | (c.primary & 0x00FFFFFF), (0xCC << 24) | (c.secondary & 0x00FFFFFF)});
        d.setCornerRadius(dp(8));
        return d;
    }

    private GradientDrawable tintAuthInput(ThemeColorExtractor.ThemeColors c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor((0x22 << 24) | (c.primary & 0x00FFFFFF));
        d.setStroke(dp(1), (0x55 << 24) | (c.primary & 0x00FFFFFF));
        d.setCornerRadius(dp(8));
        return d;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void enterImmersiveMode() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignored) { }
    }
}