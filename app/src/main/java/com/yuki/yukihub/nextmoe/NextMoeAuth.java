package com.yuki.yukihub.nextmoe;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * NextMoe 用户令牌 OAuth 管线（授权码 + PKCE S256，public client，无 client_secret）。
 * <p>
 * 端点与契约（developer.nextmoe.dev）：
 * <ul>
 *   <li>授权：GET https://account.nextmoe.com/api/v1/oauth/authorize —— 必须用系统浏览器打开
 *       （RFC 8252 §8.12，禁内嵌 WebView），带 code_challenge / state</li>
 *   <li>回调：http://127.0.0.1:{临时端口}/callback，端口无关匹配，LoopbackListener 收码</li>
 *   <li>换码/刷新：POST /oauth/token，不带 secret，带 code_verifier / refresh_token；
 *       返回裸 RFC 6749 形状 {access_token(JWT,15min), refresh_token(不透明), expires_in}</li>
 *   <li>refresh 每次轮换：旧的立即失效，拿到新的必须原地覆盖（NextMoeAuthStore.save）</li>
 * </ul>
 */
public final class NextMoeAuth {

    private static final String TAG = "NextMoe";
    // M19-1：账号中心迁移 —— 原 oauth.kungal.com → account.nextmoe.com
    // （旧域名 308 跳转会丢 Authorization 头，userinfo 必 401；issuer 也已变，旧值不再签发）
    public static final String AUTHORIZE_URL = "https://account.nextmoe.com/api/v1/oauth/authorize";
    public static final String TOKEN_URL = "https://account.nextmoe.com/api/v1/oauth/token";
    public static final String CALLBACK_HOST = "http://127.0.0.1";
    public static final String CALLBACK_PATH = "/callback";
    /**
     * OAuth client id。复用 YukiHub 现役的「YukiHub Android」客户端（与 NextMoe·未萌 快捷登录同一 client）：
     * 该 client 在 NextMoe·未萌 OAuth 后台由站主配置 redirect_uris / allowed_scopes / grants，
     * 已追加环回回调 http://127.0.0.1/callback 并勾选 catalog:read。
     * client_id 是公开标识，非机密；public client 走 PKCE，无 client_secret。
     */
    public static final String CLIENT_ID = "16cc006913d6b666c6b1a1a115f644de";
    /**
     * catalog:read 令牌可读 /v2/catalog（第 5 元数据源）；openid/profile 用于同意页身份。
     * 注意：scope 请求超出 client 的 allowed_scopes 会直接 15006 拒绝，
     * 以后做收藏夹同步时需后台先补勾 folder:read/folder:write，再把两个 scope 加回这里，
     * 且已授权用户要重新走一次授权（旧令牌不追认）。
     */
    public static final String SCOPES = "openid profile catalog:read";

    private NextMoeAuth() { }

    // ======================== PKCE ========================

    public static String randomUrlSafe(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static String pkceS256(String verifier) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    // ======================== 授权流程 ========================

    /** 授权 URL（在主线程拼好，用系统浏览器 Intent 打开）。 */
    public static String buildAuthorizeUrl(String verifier, String state, int port) throws Exception {
        String redirect = CALLBACK_HOST + ":" + port + CALLBACK_PATH;
        return AUTHORIZE_URL
                + "?client_id=" + url(CLIENT_ID)
                + "&redirect_uri=" + url(redirect)
                + "&response_type=code"
                + "&scope=" + url(SCOPES)
                + "&state=" + url(state)
                + "&code_challenge=" + url(pkceS256(verifier))
                + "&code_challenge_method=S256";
    }

    /** 阻塞收回调码的会话句柄：open() → 用 getPort() 拼 redirect_uri 开浏览器 → await()。 */
    public static LoopbackListener.Handle openLoopback() throws java.io.IOException {
        return LoopbackListener.open();
    }

    /**
     * 用授权码换令牌并落存储。
     * @param redirectUri 授权那一步用的完整回调地址（含真实端口，逐字节相同）
     * @return 失败原因；null 表示成功
     */
    public static String exchangeCode(String code, String verifier, String redirectUri) {
        try {
            String body = "grant_type=authorization_code"
                    + "&code=" + url(code)
                    + "&redirect_uri=" + url(redirectUri)
                    + "&client_id=" + url(CLIENT_ID)
                    + "&code_verifier=" + url(verifier);
            JSONObject resp = tokenPost(body);
            long expiresIn = resp.optLong("expires_in", 900L);
            saveTokens(resp, expiresIn);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "exchange code failed", t);
            return readable(t);
        }
    }

    // ======================== 刷新锁 ========================

    /**
     * 刷新串行化锁。
     * <p>
     * refresh token 是<b>一次性轮换</b>的：每次刷新成功，旧的立即作废，只下发新的。
     * 若两个线程同时拿同一个 refresh token 去刷，后到的那个必然失败，
     * 还会把先到的线程刚存好的新 token 一起清掉 —— 直接表现为「登录莫名其妙掉了」。
     * 同步功能会在 IO 线程池并发发请求，撞上这个的概率很高，所以必须串行化。
     */
    private static final Object REFRESH_LOCK = new Object();

    /** 最近一次刷新成功的时刻。用于「等锁期间已被别人刷过」的快速放行。 */
    private static volatile long lastRefreshSuccessAt = 0L;

    /**
     * 刷新令牌。refresh 每次轮换——save 覆盖写即满足契约。
     * @return 新 access token；失败返回 null
     */
    public static String refreshAccessToken() {
        return refreshAccessToken(false);
    }

    /**
     * 刷新令牌（串行化 + 双检）。
     *
     * @param force 调用方刚从服务端拿到 401，确信当前 access token 已失效。
     *              为 false 时表示只是本地判断快过期了，进锁后会先复核一次。
     * @return 新 access token；失败返回 null（<b>凭据仅在确认失效时才清除</b>）
     */
    static String refreshAccessToken(boolean force) {
        synchronized (REFRESH_LOCK) {
            long now = System.currentTimeMillis();

            // ---- 双检 1：刚被别的线程刷过 ----
            // 5 秒窗口内已经有人刷成功，直接用它的结果。
            // 这样并发进来的线程不会拿同一个（已作废的）refresh token 去撞。
            if (now - lastRefreshSuccessAt < 5000L) {
                String cur = NextMoeAuthStore.getAccessToken();
                if (!cur.isEmpty() && NextMoeAuthStore.getAccessExpiresAt() - 60_000L > now) return cur;
            }

            // ---- 双检 2：非强制场景，进锁后复核是否还有效 ----
            // 等锁期间可能已经过期→刷新完成，这里再判一次可省掉一次多余刷新。
            if (!force) {
                long exp = NextMoeAuthStore.getAccessExpiresAt();
                if (exp - 60_000L > now) {
                    String cur = NextMoeAuthStore.getAccessToken();
                    if (!cur.isEmpty()) return cur;
                }
            }

            String refresh = NextMoeAuthStore.getRefreshToken();
            if (refresh.isEmpty()) return null;
            try {
                String body = "grant_type=refresh_token"
                        + "&refresh_token=" + url(refresh)
                        + "&client_id=" + url(CLIENT_ID);
                JSONObject resp = tokenPost(body);
                long expiresIn = resp.optLong("expires_in", 900L);
                saveTokens(resp, expiresIn);
                lastRefreshSuccessAt = System.currentTimeMillis();
                return NextMoeAuthStore.getAccessToken();
            } catch (Throwable t) {
                Log.w(TAG, "refresh token failed", t);
                // 只有服务端明确说凭据失效才清除。
                // 网络超时 / 断网 / DNS 失败 / 5xx / 429 都是「暂时拿不到」，
                // 一律保留凭据等下次重试 —— 否则一次网络抖动就把用户登出了。
                if (isCredentialInvalid(t)) {
                    Log.w(TAG, "refresh token 已被服务端判定失效，清除本地凭据");
                    NextMoeAuthStore.clear();
                } else {
                    Log.w(TAG, "刷新失败但保留凭据（网络或服务端暂时不可用，非授权失效）");
                }
                return null;
            }
        }
    }

    /**
     * 判断异常是否代表「凭据确实失效」（可以安全清除）。
     * <p>
     * 白名单式判定：只有 RFC 6749 §5.2 里表示 refresh token 不可用的错误码才算。
     * 其余一切情况（含 IOException、JSON 解析失败、未知错误码）都按「暂时不可用」处理，
     * 保守地保留凭据 —— 多保留一次的代价只是下轮再试，
     * 而误清除的代价是用户必须重新授权。
     */
    private static boolean isCredentialInvalid(Throwable t) {
        if (!(t instanceof OAuthException)) return false;
        OAuthException e = (OAuthException) t;
        if (e.transientError) return false;
        String code = e.errorCode == null ? "" : e.errorCode;
        return "invalid_grant".equals(code)
                || "invalid_token".equals(code)
                || "expired_token".equals(code)
                || "unauthorized_client".equals(code);
    }

    /** 取可用 access token：未过期直接用，过期了走刷新。 */
    public static String ensureAccessToken() {
        if (!NextMoeAuthStore.isConnected()) return null;
        long exp = NextMoeAuthStore.getAccessExpiresAt();
        if (exp - 60_000L > System.currentTimeMillis()) return NextMoeAuthStore.getAccessToken();
        return refreshAccessToken();
    }

    // ======================== internals ========================

    private static void saveTokens(JSONObject resp, long expiresIn) throws Exception {
        String access = resp.optString("access_token", "");
        String refresh = resp.optString("refresh_token", "");
        if (access.isEmpty()) throw new IllegalStateException("token 响应缺少 access_token");
        // 刷新响应不总是带新 refresh_token（部分实现只轮换时才下发）；
        // 未下发时保留旧 refresh——本轮未被服务端作废。
        String keep = refresh.isEmpty() ? NextMoeAuthStore.getRefreshToken() : refresh;
        if (keep.isEmpty()) throw new IllegalStateException("token 响应缺少 refresh_token");
        NextMoeAuthStore.save(access, System.currentTimeMillis() + expiresIn * 1000L, keep, NextMoeAuthStore.getAccountLabel());
    }

    /**
     * OAuth 错误。携带人类可读消息（新旧两种线格式统一），
     * transientError=true 表示服务端瞬态故障（如 5xx / server_error），不应清掉本地凭据。
     * <p>
     * errorCode 保留 RFC 6749 §5.2 的原始错误码（invalid_grant 等），
     * 用于区分「凭据真的失效」与「暂时不可用」——这是能否安全清除凭据的唯一依据。
     */
    static final class OAuthException extends RuntimeException {
        final boolean transientError;
        final String errorCode;
        OAuthException(String message, boolean transientError) {
            this(message, transientError, null);
        }
        OAuthException(String message, boolean transientError, String errorCode) {
            super(message);
            this.transientError = transientError;
            this.errorCode = errorCode == null ? "" : errorCode;
        }
    }

    private static JSONObject tokenPost(String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "YukiHub/1.0 (Android)");
        byte[] data = form.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) { os.write(data); }
        int http = c.getResponseCode();
        String text = readAll(http >= 200 && http < 300 ? c.getInputStream() : c.getErrorStream());
        try { c.disconnect(); } catch (Throwable ignored) { }

        // 5xx 是服务端瞬态故障（NextMoe·未萌 文档 #13：保留会话并重试），不清凭据
        if (http >= 500) throw new OAuthException("OAuth 服务暂时不可用（HTTP " + http + "），请稍后重试", true);
        if (http >= 400) throw parseErrorStandard(text, http);
        return unwrap(text, http);
    }

    /** 4xx 错误体：标准 RFC 6749 §5.2 形状 {error, error_description}。 */
    private static OAuthException parseErrorStandard(String body, int http) {
        try {
            JSONObject o = new JSONObject(body == null ? "" : body);
            String err = o.optString("error", "");
            if (!err.isEmpty()) {
                return new OAuthException(o.optString("error_description", err) + " (" + err + ")",
                        "server_error".equals(err), err);
            }
        } catch (Throwable ignored) { }
        return new OAuthException("HTTP " + http + (body == null || body.isEmpty() ? "" : ": " + body), false);
    }

    /**
     * 标准格式解析（裸 RFC 6749）。NextMoe·未萌 线上格式已于 2026-09 前完成信封→标准的切换
     * （docs-kungal.nextmoe.dev #13；实测 /oauth/token 错误返回 {error, error_description}）。
     */
    private static JSONObject unwrap(String body, int http) throws Exception {
        if (body == null || body.trim().isEmpty()) return new JSONObject();
        JSONObject o = new JSONObject(body);
        if (o.has("error")) {
            String err = o.optString("error", "");
            throw new OAuthException(o.optString("error_description", err)
                    + (err.isEmpty() ? "" : " (" + err + ")"), "server_error".equals(err), err);
        }
        if (http >= 400) throw new OAuthException("HTTP " + http, false);
        return o;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static String url(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readable(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        if (m.startsWith("HTTP ") && m.contains(": {")) {
            try {
                String json = m.substring(m.indexOf(": {") + 2);
                JSONObject o = new JSONObject(json);
                String desc = o.optString("error_description", o.optString("error", ""));
                String code = o.optString("code", "");
                if (!desc.isEmpty()) return desc + (code.isEmpty() ? "" : " (" + code + ")");
                if (!code.isEmpty()) return code;
            } catch (Throwable ignored) { }
        }
        return m.isEmpty() ? "网络异常" : m;
    }
}