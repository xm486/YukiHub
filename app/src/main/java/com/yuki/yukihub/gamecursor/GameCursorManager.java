package com.yuki.yukihub.gamecursor;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.View;
import android.view.ViewGroup;

/**
 * 游戏内虚拟鼠标统一入口。
 *
 * - attachKrkr：KRKR 游戏（0.9.x 与 1.3.4 两个引擎版本通用）
 * - attachArtemis：Artemis 游戏（三个变体库通用）
 *
 * 通过 ActivityLifecycleCallbacks 在宿主 Activity 销毁时自动清理，
 * 不需要改动引擎 Activity 类。
 */
public final class GameCursorManager {
    private static final String TAG = "YukiGameCursor";
    private static volatile GameCursorOverlay active;
    /** 已挂载的宿主，避免 onActivityCreated + onActivityResumed 重复挂两个光标。 */
    private static volatile Activity attachedHost;

    private GameCursorManager() { }

    public static void attachKrkr(Activity host) {
        attach(host, true);
    }

    public static void attachArtemis(Activity host) {
        attach(host, false);
    }

    private static void attach(Activity host, boolean krkr) {
        try {
            // 幂等：同一 Activity 只挂一次。
            // YukiHubApp 在 onActivityCreated 和 onActivityResumed 都会调用，
            // 没有这道保护会挂出两个重叠光标，且 active 指向后建的那个，
            // 先建的那个变成无法交互的"幽灵"（表现为拖动时光标消失/错位）。
            if (attachedHost == host && active != null && active.isAttached()) {
                active.applyConfig();
                return;
            }
            GameCursorConfig cfg = GameCursorConfig.load(host);
            boolean enabled = krkr ? cfg.krkrEnabled : cfg.artemisEnabled;
            if (!enabled) return;

            // 换宿主或旧实例已失效：先清掉旧的，杜绝残留 View
            GameCursorOverlay previous = active;
            if (previous != null) previous.dismiss();
            active = null;

            GameCursorInjector injector = new GameCursorInjector();
            if (krkr) {
                View surface = findGlSurfaceView(host.getWindow().getDecorView());
                if (surface == null) {
                    // 视图树可能尚未挂载，等一帧再试（最多重试几次）
                    host.getWindow().getDecorView().postDelayed(() -> attach(host, true), 200);
                    return;
                }
                injector.setKrkrTarget(surface);
            } else {
                injector.setArtemisTarget();
                // 先探 native 通道：直接调引擎内部 CInputBase，不经过系统输入派发。
                // 这是 Artemis 点击的首选路径；无障碍作为降级备份保留。
                int probe = ArtemisNativeInput.probe();
                android.util.Log.i(TAG, "artemis native probe=" + probe
                        + " (-1=库未加载 0=符号缺失 1=引擎未就绪 2=可用)");
                // Artemis 在 :artemis 独立进程，无障碍服务在主进程，
                // 静态实例拿不到 —— 必须走广播桥投递到主进程执行手势。
                final Context appCtx = host.getApplicationContext();
                injector.setAccessibilityTap((x, y) ->
                        GameCursorTapBridge.requestTap(appCtx, x, y, 60));
            }
            registerAutoCleanup(host);

            GameCursorOverlay overlay = new GameCursorOverlay(host, cfg, injector);
            overlay.show();
            if (overlay.isOverlayPermissionMissing()) {
                // NativeActivity（Artemis）必须走独立窗口，没有悬浮窗权限就起不来
                android.util.Log.w(TAG, "cursor needs overlay permission (NativeActivity engine)");
                android.widget.Toast.makeText(host,
                        "虚拟鼠标需要「悬浮窗/显示在其他应用上层」权限，请在系统设置中开启",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            active = overlay;
            attachedHost = host;
            android.util.Log.i(TAG, "attached krkr=" + krkr);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "attach failed", t);
        }
    }

    private static void registerAutoCleanup(final Activity host) {
        try {
            final Application app = host.getApplication();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, android.os.Bundle s) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityResumed(Activity a) {
                    // 回到游戏：恢复光标层
                    if (a == host) {
                        GameCursorOverlay o = active;
                        if (o != null) o.setSuspended(false);
                    }
                }
                @Override public void onActivityPaused(Activity a) {
                    // 离开游戏（切后台、按 Home、拉出通知栏、跳去系统设置等）：
                    // 独立窗口（TYPE_APPLICATION_OVERLAY）不跟随 Activity 可见性，
                    // 不主动挂起的话会一直盖在系统界面上并吃掉全部触摸，
                    // 表现为「退出游戏后手机完全没法操作」。
                    if (a == host) {
                        GameCursorOverlay o = active;
                        if (o != null) o.setSuspended(true);
                    }
                }
                @Override public void onActivityStopped(Activity a) {
                    // 兜底：某些情况下 Activity 被系统直接回收，onActivityDestroyed
                    // 不一定及时触发。Stopped 时确保已挂起，绝不让窗口留在系统界面上。
                    if (a == host) {
                        GameCursorOverlay o = active;
                        if (o != null) o.setSuspended(true);
                    }
                }
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle outState) { }
                @Override public void onActivityDestroyed(Activity a) {
                    if (a == host) {
                        GameCursorOverlay o = active;
                        active = null;
                        attachedHost = null;
                        if (o != null) o.dismiss();
                        try { app.unregisterActivityLifecycleCallbacks(this); } catch (Throwable ignored) { }
                        android.util.Log.i(TAG, "auto detached");
                    }
                }
            });
        } catch (Throwable t) {
            android.util.Log.w(TAG, "register cleanup failed", t);
        }
    }

    /** 深度优先查找 DecorView 里的 GLSurfaceView（krkr 的内容视图）。 */
    private static View findGlSurfaceView(View root) {
        if (root instanceof GLSurfaceView) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findGlSurfaceView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}