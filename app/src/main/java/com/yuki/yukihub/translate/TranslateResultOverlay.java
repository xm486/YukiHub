package com.yuki.yukihub.translate;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 翻译结果悬浮层。
 *
 * 适配说明：参考 MoeTranslate 的 FloatingTextView 和 FloatingBallService 中的
 * 翻译结果窗口管理逻辑（添加/移除/拖动/穿透切换），转换为 Java。
 * 后续复制/修改相关代码时需保留原项目版权和 LGPL 声明。
 *
 * 功能：
 * - 创建带圆角背景和阴影的 TextView 作为结果层
 * - 支持 WRAP_CONTENT 自适应内容大小
 * - 支持拖动移动位置
 * - 支持点击穿透（FLAG_NOT_TOUCHABLE）
 * - 支持三种显示模式：仅译文 / 原文+译文 / 译文+原文
 * - 支持自定义字体大小、字体颜色、背景色
 * - 支持位置持久化
 */
public class TranslateResultOverlay {

    /** 显示模式：仅译文 */
    public static final int MODE_TRANSLATED_ONLY = 0;
    /** 显示模式：原文 + 译文 */
    public static final int MODE_SOURCE_THEN_TRANSLATED = 1;
    /** 显示模式：译文 + 原文 */
    public static final int MODE_TRANSLATED_THEN_SOURCE = 2;

    private final Context context;
    private final WindowManager windowManager;
    private final int windowType;

    private TextView textView;
    private WindowManager.LayoutParams params;
    private boolean added;
    private boolean touchable = false;

    // 外观配置（后续从 SharedPreferences 读取）
    private float fontSizeSp = 16f;
    private int fontColor = 0xFFE9A0B1;
    private int backgroundColor = 0xD9383838;
    private float cornerRadius = 12f;
    private float paddingDp = 16f;
    private boolean penetrable = true;

    // 拖动相关
    private float downRawX, downRawY;
    private int downX, downY;
    private boolean moved;

    /**
     * 结果层位置变化回调，用于持久化保存。
     */
    public interface OnPositionChangedListener {
        void onPositionChanged(int x, int y);
    }

    private OnPositionChangedListener positionListener;

    public TranslateResultOverlay(Context context, WindowManager windowManager, int windowType) {
        this.context = context;
        this.windowManager = windowManager;
        this.windowType = windowType;
        createTextView();
        createParams();
    }

    private void createTextView() {
        textView = new TextView(context);
        updateAppearance();
        textView.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        textView.setText("等待翻译…");
        textView.setOnTouchListener(this::onTouch);
    }

    private void createParams() {
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | (penetrable ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
    }

    /**
     * 更新外观配置并刷新 View。
     */
    public void updateAppearance() {
        if (textView == null) return;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(backgroundColor);
        shape.setCornerRadius(cornerRadius);
        textView.setBackground(shape);
        textView.setTextColor(fontColor);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
        int padPx = dp((int) paddingDp);
        textView.setPadding(padPx, (int) (padPx * 0.625f), padPx, (int) (padPx * 0.875f));
        textView.setGravity(Gravity.START);
    }

    /**
     * 添加结果层到窗口管理器。
     */
    public void show(int x, int y) {
        if (added || windowManager == null || textView == null) return;
        params.x = x;
        params.y = y;
        try {
            windowManager.addView(textView, params);
            added = true;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从窗口管理器移除结果层。
     */
    public void hide() {
        if (!added || windowManager == null || textView == null) return;
        try {
            windowManager.removeView(textView);
        } catch (Throwable ignored) {
        }
        added = false;
    }

    /**
     * 是否已添加到窗口。
     */
    public boolean isAdded() {
        return added;
    }

    /**
     * 设置显示内容。
     *
     * @param sourceText     原文
     * @param translatedText 译文
     * @param showMode       显示模式
     */
    public void setResult(String sourceText, String translatedText, int showMode) {
        if (textView == null) return;
        String display;
        switch (showMode) {
            case MODE_SOURCE_THEN_TRANSLATED:
                display = sourceText + "\n\n" + translatedText;
                break;
            case MODE_TRANSLATED_THEN_SOURCE:
                display = translatedText + "\n\n" + sourceText;
                break;
            default:
                display = translatedText;
                break;
        }
        textView.setText(display);
    }

    /**
     * 设置纯译文文本。
     */
    public void setText(String text) {
        if (textView != null) textView.setText(text);
    }

    /**
     * 开启或关闭触摸交互（拖动模式）。
     *
     * @param touchable true 可拖动，false 点击穿透
     */
    public void setTouchable(boolean touchable) {
        this.touchable = touchable;
        if (params == null || !added || windowManager == null) return;
        if (penetrable) {
            if (touchable) {
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            try {
                windowManager.updateViewLayout(textView, params);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 设置位置变化监听器。
     */
    public void setOnPositionChangedListener(OnPositionChangedListener listener) {
        this.positionListener = listener;
    }

    /**
     * 设置外观参数。
     */
    public void setAppearance(float fontSizeSp, int fontColor, int backgroundColor, float cornerRadius, float paddingDp, boolean penetrable) {
        this.fontSizeSp = fontSizeSp;
        this.fontColor = fontColor;
        this.backgroundColor = backgroundColor;
        this.cornerRadius = cornerRadius;
        this.paddingDp = paddingDp;
        this.penetrable = penetrable;
        updateAppearance();
    }

    /** 旧版兼容：不传 padding，使用默认值 */
    public void setAppearance(float fontSizeSp, int fontColor, int backgroundColor, float cornerRadius, boolean penetrable) {
        setAppearance(fontSizeSp, fontColor, backgroundColor, cornerRadius, 16f, penetrable);
    }

    /**
     * 获取当前 x 坐标。
     */
    public int getX() {
        return params != null ? params.x : 0;
    }

    /**
     * 获取当前 y 坐标。
     */
    public int getY() {
        return params != null ? params.y : 0;
    }

    private boolean onTouch(View v, MotionEvent event) {
        if (!touchable || params == null || windowManager == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downX = params.x;
                downY = params.y;
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) {
                    moved = true;
                    params.x = downX + Math.round(dx);
                    params.y = downY + Math.round(dy);
                    try {
                        windowManager.updateViewLayout(textView, params);
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (moved && positionListener != null) {
                    positionListener.onPositionChanged(params.x, params.y);
                }
                return true;
            default:
                return false;
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}