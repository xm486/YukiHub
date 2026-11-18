package com.yuki.yukihub.hikarinagi;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Hikarinagi 同步专用的授权编排（授权码 + PKCE S256，public client，环回回调）。
 * <p>
 * 与 {@code HikarinagiOAuthCallbackActivity}（快捷登录用）<b>没有任何关系</b>：
 * 回调走 {@code http://127.0.0.1:14791/callback}，由本类借助
 * {@link HikarinagiSyncClient#openLoopback()} 自己开的 {@code ServerSocket} 接收，
 * 不经过任何 Activity，也不读写 {@code yukihub_prefs} 里的登录态。
 * <b>这就是「独立登录」的实质。</b>
 *
 * <h3>时序</h3>
 * <pre>
 *   1. openLoopback()  在固定端口开始监听（必须先绑，否则浏览器回来时没人接）
 *   2. 拼授权 URL（PKCE + state），用系统浏览器打开
 *   3. 同一 socket 上阻塞收回调（5 分钟超时）
 *   4. 校验 state → 换令牌 → 落独立 prefs（hikarinagi_sync）
 * </pre>
 * PKCE verifier 只在内存中流转，从不落盘。
 *
 * <h3>为什么固定端口</h3>
 * NextMoe 靠 RFC 8252 §7.3 的「端口无关匹配」才能用随机端口；
 * Hikarinagi 文档明确要求 redirect_uri「与控制台登记的某一条完全一致，
 * 包括协议、端口、路径与结尾斜杠，不做前缀匹配」，
 * 因此端口必须是控制台里登记的那个固定值。
 *
 * <h3>同一地址 PC 版也在用，不冲突</h3>
 * {@code 127.0.0.1} 是各设备自己的回环：PC 与手机的 127.0.0.1 属于不同的
 * 网络栈，物理上互不相通，所以两端都用 14791 也不会互相干扰。
 */
public final class HikarinagiSyncAuth {

    private static final String TAG = "HikarinagiSync";

    private HikarinagiSyncAuth() { }

    /**
     * 执行一次完整授权（<b>阻塞</b>，必须在后台线程调用）。
     *
     * @param ctx 用于落令牌（内部会取 applicationContext）
     * @return 失败原因；null 表示成功（令牌已存入 {@link HikarinagiAuthStore}）
     */
    public static String connectAndWait(Context ctx) {
        HikarinagiSyncClient.LoopbackHandle handle = null;
        try {
            String state = HikarinagiSyncClient.randomUrlSafe(32);
            String verifier = HikarinagiSyncClient.randomUrlSafe(64);
            String nonce = HikarinagiSyncClient.randomUrlSafe(16);

            // 1) 先绑监听，再开浏览器：顺序反了浏览器回调回来会没人接
            handle = HikarinagiSyncClient.openLoopback();

            // 2) 拼授权 URL 并用系统浏览器打开
            //    RFC 8252 §8.12：禁止内嵌 WebView 走 OAuth
            String url = HikarinagiSyncClient.buildAuthorizeUrl(verifier, state, nonce);
            Log.i(TAG, "启动同步授权，redirect=" + HikarinagiSyncClient.REDIRECT_URI);

            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.getApplicationContext().startActivity(i);

            // 3) 阻塞收回调
            HikarinagiSyncClient.LoopbackResult result = handle.await();
            handle.close();
            handle = null;

            if ("timeout".equals(result.error)) return "授权超时（5 分钟未完成）";
            if ("io".equals(result.error)) return "本地回调监听失败";
            if (!result.error.isEmpty()) return "授权被取消或失败：" + result.error;

            // 4) 校验 state，防 CSRF / 串号
            if (!state.equals(result.state)) return "状态校验失败，请重试";
            if (result.code.trim().isEmpty()) return "回调缺少授权码";

            // 5) 换令牌（redirect_uri 必须与授权请求逐字节一致）
            HikarinagiSyncClient.Tokens tokens = HikarinagiSyncClient
                    .exchangeCode(result.code.trim(), verifier, "");
            if (tokens == null || tokens.accessToken.trim().isEmpty()) {
                return "服务端未返回访问令牌";
            }
            HikarinagiAuthStore.saveTokens(ctx, tokens);

            // 6) 顺手取账号名（纯显示用，失败不影响授权结果）
            try {
                String name = HikarinagiSyncClient.authInfo(tokens.accessToken).displayName();
                HikarinagiAuthStore.setAccountLabel(ctx, name);
            } catch (Throwable ignored) { }

            Log.i(TAG, "同步授权成功");
            return null;
        } catch (java.io.IOException e) {
            Log.w(TAG, "loopback bind failed", e);
            return "无法监听本地端口 " + HikarinagiSyncClient.LOOPBACK_PORT
                    + "（可能上次授权未退出，请重试）";
        } catch (Throwable t) {
            Log.w(TAG, "connect flow failed", t);
            return t.getMessage() == null ? "授权流程异常" : t.getMessage();
        } finally {
            if (handle != null) handle.close();
        }
    }
}