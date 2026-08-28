package com.yuki.yukihub.ons;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 虚拟按键的外观与定位的唯一实现。
 *
 * 游戏内（{@code ONScripter}）和布局编辑器（{@link OnsButtonLayoutActivity}）
 * 都调用这里，保证编辑时看到的就是游戏里的样子。之前两边各写一份，
 * 缩放系数不一致导致「预览和实际不一样」。
 */
public final class OnsButtonRenderer {

    private OnsButtonRenderer() { }

    public static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    /** 按钮圆形外观。active 为 true 时高亮（toggle 开启 / hold 按下中）。 */
    public static void applyShape(Context ctx, TextView tv, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (active) {
            bg.setColor(Color.argb(225, 74, 158, 255));
            bg.setStroke(dp(ctx, 2), Color.argb(140, 255, 255, 255));
        } else {
            bg.setColor(Color.argb(140, 10, 12, 18));
        }
        tv.setBackground(bg);
        tv.setTextColor(Color.WHITE);
    }

    /** 按钮上的文字：有标签时图标在上、标签在下；toggle 开启时图标换成停止符。 */
    public static void applyText(TextView tv, OnsButtonConfig.Item item, boolean on) {
        String icon = item.icon();
        if (OnsButtonConfig.MODE_TOGGLE.equals(item.mode()) && on) icon = "■";
        String label = item.label();
        tv.setText(label == null || label.isEmpty() ? icon : (icon + "\n" + label));
    }

    /**
     * 创建一个按钮视图（只负责外观，不绑定行为）。
     * 字号按直径比例算，任何尺寸下比例一致。
     */
    public static TextView createButton(Context ctx, OnsButtonConfig cfg,
                                        OnsButtonConfig.Item item, boolean on) {
        TextView tv = new TextView(ctx);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setLineSpacing(0f, 0.95f);

        String label = item.label();
        boolean twoLine = label != null && !label.isEmpty();
        tv.setLines(twoLine ? 2 : 1);
        tv.setTextSize(twoLine ? cfg.size * 0.155f : cfg.size * 0.37f);
        tv.setPadding(dp(ctx, 2), dp(ctx, 3), dp(ctx, 2), dp(ctx, 3));

        applyText(tv, item, on);
        applyShape(ctx, tv, OnsButtonConfig.MODE_TOGGLE.equals(item.mode()) && on);
        tv.setAlpha(alphaOf(cfg));
        return tv;
    }

    /** 创建收起按钮。collapsed 为 true 表示当前处于收起状态，显示 ☰。 */
    public static TextView createToggle(Context ctx, OnsButtonConfig cfg, boolean collapsed) {
        TextView tv = new TextView(ctx);
        tv.setText(collapsed ? "☰" : "✕");
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setLines(1);
        tv.setIncludeFontPadding(false);
        tv.setTextSize(cfg.toggleSize * 0.42f);
        applyShape(ctx, tv, false);
        tv.setAlpha(toggleAlphaOf(cfg));
        return tv;
    }

    public static float alphaOf(OnsButtonConfig cfg) {
        return Math.max(0.2f, Math.min(1f, cfg.opacity / 100f));
    }

    public static float toggleAlphaOf(OnsButtonConfig cfg) {
        return Math.max(0.35f, alphaOf(cfg) - 0.1f);
    }

    /**
     * 由中心点百分比坐标算出布局参数。
     * 不用 Gravity，纯靠 margin 定位，父容器必须是 FrameLayout。
     * 会把按钮完整地约束在父容器内，避免拖到看不见的地方。
     */
    public static FrameLayout.LayoutParams params(Context ctx, int diameter,
                                                  float xPercent, float yPercent,
                                                  int parentW, int parentH) {
        int size = dp(ctx, diameter);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        int left = Math.round(parentW * xPercent / 100f) - size / 2;
        int top = Math.round(parentH * yPercent / 100f) - size / 2;
        lp.leftMargin = clamp(left, 0, Math.max(0, parentW - size));
        lp.topMargin = clamp(top, 0, Math.max(0, parentH - size));
        return lp;
    }

    /** 由像素左上角反推中心点百分比，供拖动时写回配置。 */
    public static float toPercentX(int left, int size, int parentW) {
        if (parentW <= 0) return 50f;
        return clampF((left + size / 2f) * 100f / parentW, 0f, 100f);
    }

    public static float toPercentY(int top, int size, int parentH) {
        if (parentH <= 0) return 50f;
        return clampF((top + size / 2f) * 100f / parentH, 0f, 100f);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}