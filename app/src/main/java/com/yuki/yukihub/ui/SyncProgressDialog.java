package com.yuki.yukihub.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步进度弹窗（四源共用）。
 * <p>
 * 替换掉原先各 Dialog 里那个系统 {@code ProgressDialog}——它是白色小圈加一行字，
 * 在深蓝主题里很突兀，而且只有一个文字位，无法表达「读库 → 匹配 → 写入」的分段语义。
 * <p>
 * 本类的设计约束（都是为了不再犯之前 UI 动画的错）：
 * <ul>
 *   <li><b>进度单调递增</b>：{@link #setProgress(int,int)} 内部记 lastPercent，
 *       只允许变大；调用方传错值也不会让环往回缩。</li>
 *   <li><b>动画只做插值，不接管进度</b>：环的目标值完全由调用方的真实回调决定
 *       （例如 prepare 的 onResolveProgress），本类只做平滑过渡。</li>
 *   <li><b>无定时器</b>：不自己跑 setInterval/Handler 循环，避免多个实例互相覆盖。</li>
 * </ul>
 * <p>
 * 用法：
 * <pre>
 *   SyncProgressDialog d = SyncProgressDialog.show(activity, "Hikarinagi");
 *   d.stageMatching(12, 39);   // 匹配阶段
 *   d.stageWriting();          // 写入阶段
 *   d.finish();                // 完成，稍等 400ms 后可 close()
 * </pre>
 */
public class SyncProgressDialog {

    /** 四个阶段：读取 → 匹配 → 写入 → 完成 */
    private static final String[] STEP_LABELS = {"读取本地游戏库", "匹配条目", "写入云端", "完成"};

    private final Activity activity;
    private final Dialog dialog;

    private final SyncRingView ring;
    private final TextView pctView;
    private final TextView countView;
    private final TextView titleView;
    private final TextView stageView;
    private final List<TextView> stepDots = new ArrayList<>();
    private final List<TextView> stepTexts = new ArrayList<>();

    /** 已渲染的百分比 0~100，单调递增。 */
    private int lastPercent = 0;
    private boolean finished = false;

    private SyncProgressDialog(Activity activity, String sourceName) {
        this.activity = activity;
        this.dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int dp = dpOf(activity);

        // ---------- 卡片 ----------
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp * 18, dp * 16, dp * 18, dp * 16);
        GradientDrawable cardBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1E2740, 0xFF111930});
        cardBg.setCornerRadius(dp * 18);
        cardBg.setStroke(dp, 0x3D8AB4FF);
        card.setBackground(cardBg);

        // ---------- 左：环形进度 + 中央百分比 ----------
        android.widget.FrameLayout ringBox = new android.widget.FrameLayout(activity);
        int ringSize = dp * 92;

        ring = new SyncRingView(activity);
        ring.setColors(0xFF8AB4FF, 0xFFFF8AB3, 0x269AA4BF);
        ringBox.addView(ring, new android.widget.FrameLayout.LayoutParams(ringSize, ringSize));

        LinearLayout center = new LinearLayout(activity);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        pctView = new TextView(activity);
        pctView.setText("0%");
        pctView.setTextSize(21);
        pctView.setTextColor(0xFFF5F7FF);
        pctView.setTypeface(null, android.graphics.Typeface.BOLD);
        pctView.setGravity(Gravity.CENTER);
        center.addView(pctView);

        countView = new TextView(activity);
        countView.setTextSize(9.5f);
        countView.setTextColor(0xFF9AA4BF);
        countView.setGravity(Gravity.CENTER);
        countView.setPadding(0, dp, 0, 0);
        center.addView(countView);

        ringBox.addView(center, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(ringBox, new LinearLayout.LayoutParams(ringSize, ringSize));

        // ---------- 右：标题 + 阶段 + 步骤 ----------
        LinearLayout right = new LinearLayout(activity);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp * 16, 0, 0, 0);

        titleView = new TextView(activity);
        titleView.setText("正在同步到 " + sourceName);
        titleView.setTextSize(13);
        titleView.setTextColor(0xFFF5F7FF);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        right.addView(titleView);

        stageView = new TextView(activity);
        stageView.setText("准备中…");
        stageView.setTextSize(10.5f);
        stageView.setTextColor(0xFF9AA4BF);
        stageView.setPadding(0, dp * 3, 0, dp * 10);
        stageView.setSingleLine(true);
        stageView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        right.addView(stageView);

        for (int i = 0; i < STEP_LABELS.length; i++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (i > 0) row.setPadding(0, dp * 5, 0, 0);

            TextView dot = new TextView(activity);
            dot.setText("");
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(0x4D9AA4BF);
            dot.setBackground(dotBg);
            row.addView(dot, new LinearLayout.LayoutParams(dp * 6, dp * 6));
            stepDots.add(dot);

            TextView label = new TextView(activity);
            label.setText(STEP_LABELS[i]);
            label.setTextSize(10);
            label.setTextColor(0xFF9AA4BF);
            label.setPadding(dp * 7, 0, 0, 0);
            row.addView(label);
            stepTexts.add(label);

            right.addView(row);
        }

        card.addView(right, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dialog.setContentView(card);
        dialog.setCancelable(false);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.6f;
            // 横屏下卡片宽度占比；高度自适应
            lp.width = Math.min((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.52f), dp * 430);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }

        setStepState(0, STATE_ACTIVE);
    }

    private static final int STATE_IDLE = 0;
    private static final int STATE_ACTIVE = 1;
    private static final int STATE_DONE = 2;

    /** 创建并显示。 */
    public static SyncProgressDialog show(Activity activity, String sourceName) {
        SyncProgressDialog d = new SyncProgressDialog(activity, sourceName);
        if (activity != null && !activity.isFinishing()
                && !(Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
            d.dialog.show();
        }
        return d;
    }

    /** 阶段 1：读取本地库（0% 起步）。 */
    public void stageReading() {
        stageView.setText("读取本地游戏库…");
        setProgress(0, 0, 0f);
        setStepState(0, STATE_ACTIVE);
    }

    /**
     * 阶段 2：匹配中。
     *
     * @param done  已完成条数
     * @param total 总条数
     */
    public void stageMatching(int done, int total) {
        if (finished) return;
        stageView.setText("匹配中… " + done + " / " + total);
        setStepState(0, STATE_DONE);
        setStepState(1, STATE_ACTIVE);
        // 匹配占 10% ~ 88%
        float ratio = 0.10f + 0.78f * (total <= 0 ? 0f : Math.min(1f, done / (float) total));
        setProgress(done, total, ratio);
    }

    /** 阶段 3：写入云端。 */
    public void stageWriting() {
        if (finished) return;
        stageView.setText("写入云端…");
        setStepState(0, STATE_DONE);
        setStepState(1, STATE_DONE);
        setStepState(2, STATE_ACTIVE);
        // 写入占 88% ~ 100%；这里先到 94%，真正的 100% 由 finish() 给
        applyPercent(94);
    }

    /** 阶段 4：完成。 */
    public void finish() {
        if (finished) return;
        finished = true;
        stageView.setText("已完成");
        setStepState(0, STATE_DONE);
        setStepState(1, STATE_DONE);
        setStepState(2, STATE_DONE);
        setStepState(3, STATE_DONE);
        applyPercent(100);
    }

    /** 直接设置进度（不改变阶段文案）。percent 会被夹紧且保证单调。 */
    public void setProgress(int done, int total, float ratio) {
        if (finished) return;
        int pct = Math.round(ratio * 100f);
        applyPercent(pct);
        countView.setText(done + " / " + total);
    }

    /** 核心：只升不降。 */
    private void applyPercent(int pct) {
        int v = pct;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        if (v < lastPercent) return;   // 单调保护，绝不回退
        lastPercent = v;
        ring.setProgress(v / 100f);
        pctView.setText(v + "%");
    }

    private void setStepState(int index, int state) {
        if (index < 0 || index >= stepDots.size()) return;
        TextView dot = stepDots.get(index);
        TextView label = stepTexts.get(index);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (state == STATE_DONE) {
            bg.setColor(0xFF34C759);
            label.setTextColor(0xDDF5F7FF);
            dot.setBackground(bg);
        } else if (state == STATE_ACTIVE) {
            bg.setColor(0xFF8AB4FF);
            label.setTextColor(0xFF8AB4FF);
            dot.setBackground(bg);
        } else {
            bg.setColor(0x4D9AA4BF);
            label.setTextColor(0xFF9AA4BF);
            dot.setBackground(bg);
        }
    }

    /** 关闭，带淡出。 */
    public void close() {
        try {
            if (dialog.isShowing()) dialog.dismiss();
        } catch (Throwable ignored) { }
    }

    public boolean isShowing() {
        try {
            return dialog.isShowing();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int dpOf(Activity a) {
        return (int) (a.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 便捷：把 View 用淡入显示（供外部做入场动画）。 */
    public static void fadeIn(View v) {
        if (v == null) return;
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(180).start();
    }
}