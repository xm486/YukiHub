package com.yuki.yukihub.hikarinagi;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Hikarinagi 用户令牌的存储与刷新（游玩状态同步专用）。
 *
 * <h3>为什么单独一个 prefs 文件</h3>
 * 用 {@code hikarinagi_sync}，与以下三处<b>完全隔离</b>：
 * <ul>
 *   <li>{@code yukihub_prefs} —— 「Hikarinagi 快捷登录」在那里存 pending 状态，
 *       那是登录 YukiHub 用的，与同步无关；</li>
 *   <li>{@code nextmoe_auth} / {@code bangumi_sync} —— 其它同步源，彼此不共享。</li>
 * </ul>
 * 这样即使同步授权流程被中途放弃，也绝不会影响用户已有的快捷登录状态。
 *
 * <h3>refresh token 轮换</h3>
 * Hikarinagi 每次刷新都会轮换 refresh token、旧的立即失效，所以刷新必须串行化：
 * 两个线程拿同一个 refresh token 并发去刷，后到的必然失败，还会把先到的
 * 刚存好的新令牌一起覆盖掉 —— 直接表现为「授权莫名其妙掉了」。
 */
public final class HikarinagiAuthStore {

    private static final String TAG = "HikarinagiSync";

    /** 独立 prefs 文件，绝不与快捷登录或其它同步源混用。 */
    public static final String PREFS_NAME = "hikarinagi_sync";

    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_EXPIRES_AT = "access_expires_at";
    private static final String KEY_ACCOUNT = "account_label";
    private static final String KEY_LAST_SYNC_AT = "last_sync_at";

    /** 授权流程的临时状态（只在从点「去授权」到回调之间有效）。 */
    public static final String KEY_PENDING_STATE = "pending_state";
    public static final String KEY_PENDING_VERIFIER = "pending_verifier";
    public static final String KEY_PENDING_NONCE = "pending_nonce";
    public static final String KEY_PENDING_STARTED_AT = "pending_started_at";

    /** 授权状态的有效期：超过这个时间视为过期，避免用过期的 verifier 去换码。 */
    private static final long PENDING_TTL_MS = 10L * 60L * 1000L;

    private HikarinagiAuthStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ======================== 读写凭据 ========================

    public static void saveTokens(Context c, HikarinagiSyncClient.Tokens t) {
        if (t == null || t.accessToken.trim().isEmpty()) return;
        prefs(c).edit()
                .putString(KEY_ACCESS, t.accessToken)
                .putString(KEY_REFRESH, t.refreshToken == null ? "" : t.refreshToken)
                .putLong(KEY_EXPIRES_AT, t.expiresAt)
                .apply();
    }

    public static String getAccessToken(Context c) {
        return prefs(c).getString(KEY_ACCESS, "");
    }

    public static String getRefreshToken(Context c) {
        return prefs(c).getString(KEY_REFRESH, "");
    }

    public static long getExpiresAt(Context c) {
        return prefs(c).getLong(KEY_EXPIRES_AT, 0L);
    }

    public static void setAccountLabel(Context c, String label) {
        prefs(c).edit().putString(KEY_ACCOUNT, label == null ? "" : label).apply();
    }

    public static String getAccountLabel(Context c) {
        return prefs(c).getString(KEY_ACCOUNT, "");
    }

    public static long lastSyncAt(Context c) {
        return prefs(c).getLong(KEY_LAST_SYNC_AT, 0L);
    }

    public static void markSyncedAt(Context c, long at) {
        prefs(c).edit().putLong(KEY_LAST_SYNC_AT, at).apply();
    }

    /** 是否已授权（纯本地判断，不发网络请求）。 */
    public static boolean isConnected(Context c) {
        String t = getAccessToken(c);
        return t != null && !t.trim().isEmpty();
    }

    /** 清空凭据（用户主动「退出授权」时用）。 */
    public static void clear(Context c) {
        prefs(c).edit()
                .remove(KEY_ACCESS)
                .remove(KEY_REFRESH)
                .remove(KEY_EXPIRES_AT)
                .remove(KEY_ACCOUNT)
                .apply();
    }

    // ======================== 授权流程临时状态 ========================

    public static void savePending(Context c, String state, String verifier, String nonce) {
        prefs(c).edit()
                .putString(KEY_PENDING_STATE, state)
                .putString(KEY_PENDING_VERIFIER, verifier)
                .putString(KEY_PENDING_NONCE, nonce)
                .putLong(KEY_PENDING_STARTED_AT, System.currentTimeMillis())
                .apply();
    }

    /**
     * 取出并校验回调参数是否属于本流程。
     * <p>
     * 返回 null 表示<b>不是同步授权流程</b>（可能是快捷登录的回调），
     * 调用方据此把控制权交还给原来的登录链路。
     *
     * @return 校验通过的 verifier；不匹配或已过期返回 null
     */
    public static String takePendingVerifier(Context c, String callbackState) {
        SharedPreferences p = prefs(c);
        String state = p.getString(KEY_PENDING_STATE, "");
        String verifier = p.getString(KEY_PENDING_VERIFIER, "");
        long startedAt = p.getLong(KEY_PENDING_STARTED_AT, 0L);

        // 没有发起过同步授权 → 与本流程无关
        if (state == null || state.trim().isEmpty()) return null;
        // state 不匹配 → 不是本次流程的回调
        if (callbackState == null || !state.equals(callbackState)) return null;
        // 过期
        if (startedAt <= 0 || System.currentTimeMillis() - startedAt > PENDING_TTL_MS) {
            Log.w(TAG, "同步授权已超时，忽略本次回调");
            clearPending(c);
            return null;
        }
        if (verifier == null || verifier.trim().isEmpty()) {
            clearPending(c);
            return null;
        }
        return verifier;
    }

    public static void clearPending(Context c) {
        prefs(c).edit()
                .remove(KEY_PENDING_STATE)
                .remove(KEY_PENDING_VERIFIER)
                .remove(KEY_PENDING_NONCE)
                .remove(KEY_PENDING_STARTED_AT)
                .apply();
    }

    // ======================== 刷新（串行化） ========================

    private static final Object REFRESH_LOCK = new Object();
    /** 最近一次刷新成功的时刻，用于「等锁期间已被别人刷过」的快速放行。 */
    private static volatile long lastRefreshSuccessAt = 0L;

    /**
     * 取可用的 access token：未过期直接用，快过期则刷新。
     *
     * @return access token；拿不到（未授权或刷新失败）返回 null
     */
    public static String ensureAccessToken(Context c) {
        if (!isConnected(c)) return null;
        long exp = getExpiresAt(c);
        if (exp - 60_000L > System.currentTimeMillis()) return getAccessToken(c);
        return refresh(c, false);
    }

    /**
     * 刷新令牌（串行化 + 双检）。
     *
     * @param force 调用方刚收到 401，确信当前 access token 已失效
     * @return 新 access token；失败返回 null（<b>仅在确认失效时才清凭据</b>）
     */
    public static String refresh(Context c, boolean force) {
        synchronized (REFRESH_LOCK) {
            long now = System.currentTimeMillis();

            // 双检 1：刚被别的线程刷过，直接用结果
            if (now - lastRefreshSuccessAt < 5000L) {
                String cur = getAccessToken(c);
                if (!cur.isEmpty() && getExpiresAt(c) - 60_000L > now) return cur;
            }

            // 双检 2：非强制场景，进锁后复核
            if (!force) {
                long exp = getExpiresAt(c);
                if (exp - 60_000L > now) {
                    String cur = getAccessToken(c);
                    if (!cur.isEmpty()) return cur;
                }
            }

            String refreshToken = getRefreshToken(c);
            if (refreshToken == null || refreshToken.trim().isEmpty()) {
                Log.w(TAG, "没有 refresh token，无法续期");
                return null;
            }

            try {
                HikarinagiSyncClient.Tokens t = HikarinagiSyncClient.refresh(refreshToken);
                // 轮换：必须原地覆盖，不能保留旧的 refresh token
                saveTokens(c, t);
                lastRefreshSuccessAt = System.currentTimeMillis();
                return t.accessToken;
            } catch (HikarinagiSyncClient.OAuthTransientException e) {
                // 服务端瞬态故障：保留凭据，下次再试
                Log.w(TAG, "刷新失败但保留凭据（服务端暂时不可用）", e);
                return null;
            } catch (HikarinagiSyncClient.OAuthException e) {
                String code = e.errorCode == null ? "" : e.errorCode;
                boolean invalid = "invalid_grant".equals(code)
                        || "invalid_token".equals(code)
                        || "expired_token".equals(code)
                        || "unauthorized_client".equals(code);
                if (invalid) {
                    Log.w(TAG, "refresh token 已被服务端判定失效，清除本地凭据");
                    clear(c);
                } else {
                    // 其它错误（含未知码）一律保守保留：多留一次的代价只是下轮再试，
                    // 误清除的代价是用户必须重新授权。
                    Log.w(TAG, "刷新失败但保留凭据（错误码 " + code + "）");
                }
                return null;
            } catch (Throwable t) {
                // 网络异常等：保留凭据
                Log.w(TAG, "刷新异常但保留凭据（网络问题？）", t);
                return null;
            }
        }
    }
}