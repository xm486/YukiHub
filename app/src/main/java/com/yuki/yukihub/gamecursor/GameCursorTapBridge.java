package com.yuki.yukihub.gamecursor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * 跨进程点击注入桥。
 *
 * 背景：无障碍服务（YukiScreenshotAccessibilityService）没有声明 android:process，
 * 跑在**主进程**；而 Artemis Activity 跑在 `:artemis` 独立进程。
 * ScreenshotServiceManager 是静态字段，跨进程读不到 —— 因此 Artemis 进程里
 * 直接调 tap 永远拿不到服务实例，点击必然无效。
 *
 * 解决：Artemis 进程发本地广播，主进程接收后调用无障碍手势。
 * 用显式包名 + 非导出接收器，不对外暴露。
 */
public final class GameCursorTapBridge {
    private static final String TAG = "YukiGameCursor";
    public static final String ACTION_TAP = "com.yuki.yukihub.gamecursor.ACTION_TAP";
    private static final String EXTRA_X = "x";
    private static final String EXTRA_Y = "y";
    private static final String EXTRA_DURATION = "duration";

    private GameCursorTapBridge() { }

    /** 在游戏进程调用：请求主进程执行一次无障碍点击。 */
    public static void requestTap(Context ctx, int x, int y, long durationMs) {
        if (ctx == null) return;
        // 同进程已持有服务实例时直接调用（主进程内的场景），省一次广播往返
        try {
            if (com.yuki.yukihub.translate.ScreenshotServiceManager.isReady()) {
                com.yuki.yukihub.translate.YukiScreenshotAccessibilityService.tap(x, y, durationMs);
                return;
            }
        } catch (Throwable ignored) { }
        try {
            Intent i = new Intent(ACTION_TAP);
            i.setPackage(ctx.getPackageName());
            // FLAG_RECEIVER_FOREGROUND：游戏进程在前台，但主进程可能被判为后台，
            // 不加这个标记广播可能被延迟投递，点击手感会明显卡顿。
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            i.putExtra(EXTRA_X, x);
            i.putExtra(EXTRA_Y, y);
            i.putExtra(EXTRA_DURATION, durationMs);
            ctx.sendBroadcast(i);
            android.util.Log.d(TAG, "tap bridge -> (" + x + "," + y + ")");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "requestTap failed", t);
        }
    }

    /**
     * 在主进程注册接收器（由 Application 启动时调用）。
     * 接到请求后走无障碍手势派发；服务未开启时只记日志，不抛异常。
     */
    public static void registerHost(Context appCtx) {
        if (appCtx == null) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !ACTION_TAP.equals(intent.getAction())) return;
                    int x = intent.getIntExtra(EXTRA_X, -1);
                    int y = intent.getIntExtra(EXTRA_Y, -1);
                    long d = intent.getLongExtra(EXTRA_DURATION, 60L);
                    if (x < 0 || y < 0) return;
                    boolean ready = com.yuki.yukihub.translate.ScreenshotServiceManager.isReady();
                    android.util.Log.d(TAG, "tap bridge recv (" + x + "," + y
                            + ") accessibilityReady=" + ready);
                    com.yuki.yukihub.translate.YukiScreenshotAccessibilityService.tap(x, y, d);
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_TAP);
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appCtx.registerReceiver(receiver, filter);
            }
            android.util.Log.i(TAG, "tap bridge host registered");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "registerHost failed", t);
        }
    }
}