package com.yuki.yukihub.nextmoe;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * NextMoe 授权流程编排（后台线程调用）。
 * <p>
 * 时序：open 环回监听拿端口 → 拼授权 URL（PKCE+state）→ 系统浏览器打开 →
 * 同一 socket 阻塞收回调 → state 校验 → 换码（redirect_uri 逐字节一致）。
 * PKCE verifier 只在内存中流转，从不落盘。
 */
public final class NextMoeConnectFlow {

    private static final String TAG = "NextMoe";

    private NextMoeConnectFlow() { }

    /**
     * 执行一次完整授权。
     * @return 失败原因；null 表示成功（令牌已入 NextMoeAuthStore）
     */
    public static String connectAndWait() {
        LoopbackListener.Handle handle = null;
        try {
            String verifier = NextMoeAuth.randomUrlSafe(48);
            String state = NextMoeAuth.randomUrlSafe(16);
            handle = NextMoeAuth.openLoopback();
            String redirect = NextMoeAuth.CALLBACK_HOST + ":" + handle.getPort() + NextMoeAuth.CALLBACK_PATH;
            String url = NextMoeAuth.buildAuthorizeUrl(verifier, state, handle.getPort());

            // 系统浏览器（RFC 8252 §8.12 禁内嵌 WebView）。无浏览器时 fail-fast
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            com.yuki.yukihub.nextmoe.NextMoeContexts.app().startActivity(i);

            LoopbackListener.Result result = handle.await();
            handle.close();
            handle = null;

            if ("timeout".equals(result.error)) return "授权超时（5 分钟未完成）";
            if ("io".equals(result.error)) return "本地回调监听失败";
            if (result.error != null && !result.error.isEmpty()) return "授权被取消或失败：" + result.error;
            if (result.state == null || !state.equals(result.state)) return "状态校验失败，请重试";
            if (result.code == null || result.code.trim().isEmpty()) return "回调缺少授权码";

            return NextMoeAuth.exchangeCode(result.code.trim(), verifier, redirect);
        } catch (Throwable t) {
            Log.w(TAG, "connect flow failed", t);
            return t.getMessage() == null ? "授权流程异常" : t.getMessage();
        } finally {
            if (handle != null) handle.close();
        }
    }
}