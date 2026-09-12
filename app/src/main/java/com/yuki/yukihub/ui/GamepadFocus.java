package com.yuki.yukihub.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;

import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;

/**
 * 全局手柄焦点适配（M14）。
 *
 * <p><b>为什么需要它</b>：整个 app（游戏库 MainActivity、启动器主页 HomeActivity 等）
 * 之前都只依赖系统默认的焦点效果 —— 在深色主题下就是"一点点变灰"，
 * 手柄用户根本看不出焦点落在哪个控件上。
 *
 * <p><b>做法</b>：不去手改几百个布局，而是在 Activity 内容视图上装一个
 * {@link ViewTreeObserver.OnGlobalFocusChangeListener}：
 * <ul>
 *   <li>失焦的控件恢复原状（缩放归 1、去掉高亮前景）；</li>
 *   <li>获焦的控件加上 {@code fg_focus_highlight}（粉色描边）+ 轻微放大 + 滚进可视区。</li>
 * </ul>
 * 这样**新增布局也自动适配**，不会漏。
 *
 * <p>另外 {@link #makeFocusable(View)} 会把常见可点控件补上 {@code focusable=true} ——
 * 有些控件（比如代码里 new 出来的 TextView + setOnClickListener）默认拿不到焦点，
 * 手柄再怎么按也走不到它上面。
 */
public final class GamepadFocus {

    /** 焦点态放大倍率（卡片类稍大，普通按钮更含蓄） */
    private static final float SCALE_CARD = 1.04f;
    private static final float SCALE_SMALL = 1.06f;
    private static final long ANIM_MS = 140L;
    /** M17：缩放幅度可调（0 = 只描边不缩放；100 = 默认） */
    private static float scaleFactor = 1f;

    /** M17：由设置下发焦点缩放幅度（百分比） */
    public static void setScalePercent(int percent) {
        scaleFactor = Math.max(0f, Math.min(1.5f, percent / 100f));
    }

    /** 按当前幅度算出实际缩放值（0 → 1.0，即不缩放） */
    private static float scaleOf(float base) {
        return 1f + (base - 1f) * scaleFactor;
    }

    private GamepadFocus() { }

    /**
     * 给整个 Activity 装上手柄焦点视觉。在 {@code setContentView(...)} 之后调用一次即可。
     */
    public static void attach(Activity activity) {
        if (activity == null) { return; }
        final View root = activity.findViewById(android.R.id.content);
        if (root == null) { return; }
        attach(root);
    }

    /** 给某个子树装上（对话框 / 弹窗用） */
    public static void attach(final View root) {
        if (root == null) { return; }
        try {
            root.getViewTreeObserver().addOnGlobalFocusChangeListener(
                    new ViewTreeObserver.OnGlobalFocusChangeListener() {
                        @Override
                        public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                            if (oldFocus != null) { clear(oldFocus); }
                            if (newFocus != null) { highlight(newFocus); }
                        }
                    });
            makeFocusableTree(root);
        } catch (Throwable ignored) { }
    }

    /** 焦点视觉：描边前景 + 轻微放大 + 抬高层级 + 滚进可视区 */
    private static void highlight(View v) {
        try {
            if (isTextInput(v)) { return; }   // 输入框有光标，不需要额外描边
            v.setForeground(androidx.core.content.ContextCompat.getDrawable(
                    v.getContext(), R.drawable.fg_focus_highlight));
            float scale = scaleOf(isSmallControl(v) ? SCALE_SMALL : SCALE_CARD);
            v.animate().cancel();
            v.animate().scaleX(scale).scaleY(scale)
                    .setDuration(ANIM_MS).setInterpolator(new DecelerateInterpolator()).start();
            v.setZ(8f);
            // 焦点滚进可视区（列表 / ScrollView 里都有效）
            v.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()), false);
        } catch (Throwable ignored) { }
    }

    private static void clear(View v) {
        try {
            if (isTextInput(v)) { return; }
            v.setForeground(null);
            v.animate().cancel();
            v.animate().scaleX(1f).scaleY(1f)
                    .setDuration(ANIM_MS).setInterpolator(new DecelerateInterpolator()).start();
            v.setZ(0f);
        } catch (Throwable ignored) { }
    }

    private static boolean isTextInput(View v) { return v instanceof EditText; }

    private static boolean isSmallControl(View v) {
        return v instanceof Button || v instanceof ImageButton
                || v instanceof CompoundButton || v instanceof Spinner;
    }

    /**
     * 递归把"能点但拿不到焦点"的控件补上 focusable。
     *
     * <p>RecyclerView / ListView 的子项由适配器动态生成，这里只处理容器本身，
     * 子项靠 {@link #makeFocusable(View)}（适配器 onBind 里调）或布局里的 focusable。
     */
    public static void makeFocusableTree(View v) {
        if (v == null) { return; }
        if (v instanceof RecyclerView || v instanceof AdapterView) {
            makeFocusable(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                makeFocusableTree(g.getChildAt(i));
            }
        }
        if (v.isClickable() || v instanceof Button || v instanceof ImageButton
                || v instanceof CompoundButton || v instanceof Spinner) {
            makeFocusable(v);
        }
    }

    /** 单个控件：允许获得焦点（含手柄的"非触摸模式"） */
    public static void makeFocusable(View v) {
        if (v == null) { return; }
        try {
            v.setFocusable(true);
            v.setFocusableInTouchMode(false);   // 触摸时不抢焦点，只服务手柄/键盘
            if (v.getDefaultFocusHighlightEnabled()) {
                // 关掉系统那层几乎看不见的默认高亮，避免和我们的描边叠加
                v.setDefaultFocusHighlightEnabled(false);
            }
        } catch (Throwable ignored) { }
    }
}