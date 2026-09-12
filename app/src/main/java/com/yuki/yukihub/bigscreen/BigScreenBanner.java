package com.yuki.yukihub.bigscreen;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/**
 * 顶部提示条（spec §S7）。
 *
 * <p>从屏幕上方滑入 + 淡入，停留后自动滑出。用于"已收藏 / 已排序 / 已绑定 PV"这类**操作反馈**。
 *
 * <p>M14-3：用户两轮反馈后**回退到最简**——不要回弹、不要脉冲、不要渐变花活（"还是简单一点"）。
 * 入场就是一次干净的淡入 + 轻微下移（Decelerate 200ms），出场对称淡出。
 */
public class BigScreenBanner {
    /** 停留时长：2.0s（1.8s 太短、4s 太长，取中；可被设置覆盖） */
    private static final long SHOW_MS = 2000L;
    private static final float SLIDE_DP = 20f;
    private final View root;
    private final TextView text;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideTask;
    /** M17：入场动画期间抑制提示（"手柄已连接"不该盖在启动动画上） */
    private boolean suppressed = false;
    /** M17：可配置的停留时长 */
    private long holdMs = SHOW_MS;

    public BigScreenBanner(View root, TextView text) {
        this.root = root;
        this.text = text;
    }

    /** M17：入场动画播放中 = 不显示任何提示条 */
    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        if (suppressed) { hide(); }
    }

    /** M17：设置停留时长（来自"横幅提示时长"选项） */
    public void setHoldMs(long ms) {
        this.holdMs = Math.max(800L, Math.min(6000L, ms));
    }

    public boolean isSuppressed() { return suppressed; }

    public boolean isShowing() {
        return root != null && root.getVisibility() == View.VISIBLE;
    }

    /** 显示一条提示（重复调用会重置停留时间） */
    public void show(String message) {
        show(message, 0);
    }

    /**
     * 显示一条提示，可在文字左侧带一个小图标（M18-2）。
     *
     * <p>用户要的是"**手柄已连接的提示里**带手柄图标"，不是顶栏常驻图标。
     * 这里用 TextView 的 compound drawable 实现，不用改布局。
     */
    public void show(String message, int iconRes) {
        if (root == null || text == null || message == null || message.isEmpty()) { return; }
        if (suppressed) { return; }   // M17：入场动画期间不显示
        cancelPending();

        if (iconRes != 0) {
            text.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
            text.setCompoundDrawablePadding(dp(6));
            if (text instanceof TextView) {
                text.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(0xFF9BB4E8));
            }
        } else {
            text.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        }
        text.setText(message);
        root.setVisibility(View.VISIBLE);
        root.animate().cancel();
        if (root.getAlpha() < 0.9f) {
            root.setAlpha(0f);
            root.setTranslationY(-dp(SLIDE_DP));
        } else {
            root.setAlpha(1f);
            root.setTranslationY(0f);
        }
        // 干净的淡入 + 轻微下移，简单、不抢戏
        root.animate()
                .alpha(1f).translationY(0f)
                .setDuration(200L)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        hideTask = this::hide;
        handler.postDelayed(hideTask, holdMs);
    }

    public void hide() {
        cancelPending();
        if (root == null) { return; }
        root.animate().cancel();
        root.animate()
                .alpha(0f).translationY(-dp(SLIDE_DP))
                .setDuration(180L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> root.setVisibility(View.GONE))
                .start();
    }

    public void cancel() {
        cancelPending();
        if (root != null) { root.animate().cancel(); }
    }

    private void cancelPending() {
        if (hideTask != null) {
            handler.removeCallbacks(hideTask);
            hideTask = null;
        }
    }

    private int dp(float value) {
        return Math.round(value * root.getResources().getDisplayMetrics().density);
    }
}