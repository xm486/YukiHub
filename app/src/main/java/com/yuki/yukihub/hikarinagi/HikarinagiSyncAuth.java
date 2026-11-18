package com.yuki.yukihub.hikarinagi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

/**
 * Hikarinagi 同步专用的授权发起器（授权码 + PKCE S256，public client）。
 *
 * <h3>为什么复用 {@code yukihub://hikarinagi/callback}</h3>
 * Hikarinagi 控制台里一个 client 只能登记一个回调地址，而「Hikarinagi 快捷登录」
 * 已经占用了这个地址。好在 OAuth 的 {@code state} 参数本就是用来区分
 * 「这是哪一次授权请求」的，所以：
 * <ul>
 *   <li>本流程发起时把 state / verifier / nonce 写进<b>独立的 prefs</b>
 *       （{@code hikarinagi_sync}，而快捷登录用 {@code yukihub_prefs}）；</li>
 *   <li>回调 Activity 到达时，先问同步流程「这个 state 是你的吗」；</li>
 *   <li>是 → 同步流程换令牌并保存，不碰 YukiHub 登录态；</li>
 *   <li>否 → 原样交还给快捷登录逻辑，行为与改动前完全一致。</li>
 * </ul>
 * 两边 state 都是 32 字节随机串，撞车概率可以忽略；即使真的撞了，
 * 也是「同步流程认领了快捷登录的回调」，此时同步流程会用错误的 verifier
 * 换码失败并报错，<b>不会</b>写入任何错误凭据，也不会破坏登录态。
 *
 * <h3>必须用系统浏览器</h3>
 * RFC 8252 §8.12 禁止用内嵌 WebView 走 OAuth（会拿到应用自己控制不了的会话），
 * 所以这里用 {@link Intent#ACTION_VIEW} 交给系统浏览器。
 */
public final class HikarinagiSyncAuth {

    private static final String TAG = "HikarinagiSync";

    private HikarinagiSyncAuth() { }

    /**
     * 发起授权。已在主线程。
     * <p>
     * 写入 pending 状态后打开系统浏览器；结果由
     * {@code HikarinagiOAuthCallbackActivity} 收，再分流回本流程。
     */
    public static void start(Activity activity) {
        if (activity == null) return;
        try {
            String state = HikarinagiSyncClient.randomUrlSafe(32);
            String verifier = HikarinagiSyncClient.randomUrlSafe(64);
            String nonce = HikarinagiSyncClient.randomUrlSafe(16);

            HikarinagiAuthStore.savePending(activity, state, verifier, nonce);

            String url = HikarinagiSyncClient.buildAuthorizeUrl(verifier, state, nonce);
            Log.i(TAG, "启动同步授权，scope=" + HikarinagiSyncClient.SCOPES);

            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "启动同步授权失败", t);
            Toast.makeText(activity, "无法打开 Hikarinagi 授权页："
                    + (t.getMessage() == null ? "请检查浏览器" : t.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 同步流程是否正在等回调（纯本地判断）。
     * <p>
     * 用于回调 Activity 首次绑定时决定是否展示「正在完成同步授权」的提示。
     */
    public static boolean hasPending(Context c) {
        return c.getSharedPreferences(HikarinagiAuthStore.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(HikarinagiAuthStore.KEY_PENDING_STATE, "") != null
                && !c.getSharedPreferences(HikarinagiAuthStore.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(HikarinagiAuthStore.KEY_PENDING_STATE, "").trim().isEmpty();
    }
}