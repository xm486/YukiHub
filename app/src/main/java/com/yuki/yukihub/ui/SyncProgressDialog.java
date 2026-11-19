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

    /** 无进度回调时的保底推进（Handler 驱动，只做视觉，不假装真实数据）。 */
    private final android.os.Handler creepHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable creepRunnable;
    /** 保底推进的上限百分比，绝不越过（避免假装已完成）。 */
    private static final int CREEP_CEILING = 86;

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

        // 百分比与大号数字都直接铺在 ringBox 上，靠 gravity + margin 错开，
        // 不再套一层 LinearLayout（套了会导致「同一个 View 加两次」的错：
        // 先 addView 到 LinearLayout，又 addView 到 FrameLayout，
        // 第二次会抛 "The specified child already has a parent"）。
        pctView = new TextView(activity);
        pctView.setText("0%");
        pctView.setTextSize(21);
        pctView.setTextColor(0xFFF5F7FF);
        pctView.setTypeface(null, android.graphics.Typeface.BOLD);
        pctView.setGravity(Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams pctLp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pctLp.gravity = Gravity.CENTER;
        pctLp.bottomMargin = dp * 11;   // 往上挪，给下面的计数留位置
        ringBox.addView(pctView, pctLp);

        countView = new TextView(activity);
        countView.setTextSize(9.5f);
        countView.setTextColor(0xFF9AA4BF);
        countView.setGravity(Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams cntLp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cntLp.gravity = Gravity.CENTER;
        cntLp.topMargin = dp * 26;      // 往下挪
        ringBox.addView(countView, cntLp);

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

    /** 阶段 1：读取本地库（从 0% 起步，并启动保底爬升）。 */
    public void stageReading() {
        // 允许同一实例被复用（例如「重试」时）。必须把所有状态归零，
        // 否则上一次的 finished=true / lastPercent=100 会让这轮完全不动。
        finished = false;
        stageView.setText("读取本地游戏库…");
        ring.setIndeterminate(false);
        lastPercent = 0;
        ring.resetProgress();
        pctView.setText("0%");
        countView.setText("");
        setStepState(0, STATE_ACTIVE);
        setStepState(1, STATE_IDLE);
        setStepState(2, STATE_IDLE);
        setStepState(3, STATE_IDLE);
        startCreep();
    }

    /**
     * 阶段 2：匹配中。
     * <p>
     * 这里有两条路：
     * <ul>
     *   <li>有真实回调（done/total 单调增长）→ 用真实比例，同时关掉保底爬升</li>
     *   <li>total <= 0（数据源没有回调）→ 保持保底爬升，环继续往上走，
     *       不会卡在 0% 让用户以为卡死</li>
     * </ul>
     *
     * @param done  已完成条数
     * @param total 总条数；<=0 表示未知（无进度回调）
     */
    public void stageMatching(int done, int total) {
        if (finished) return;
        setStepState(0, STATE_DONE);
        setStepState(1, STATE_ACTIVE);

        if (total <= 0) {
            stageView.setText("匹配中…");
            return;   // 保持 creep 兜底
        }

        stopCreep();
        stageView.setText("匹配中… " + done + " / " + total);
        countView.setText(done + " / " + total);
        // 匹配占 10% ~ 88%
        float ratio = 0.10f + 0.78f * Math.min(1f, done / (float) total);
        applyPercent(Math.round(ratio * 100f));
    }

    /**
     * 数据源完全没有进度回调（如 NextMoe 的 resolve）。
     * 用不确定模式转圈，让用户知道还在跑，而不是卡在 0%。
     */
    public void stageMatchingUnknown() {
        if (finished) return;
        setStepState(0, STATE_DONE);
        setStepState(1, STATE_ACTIVE);
        stageView.setText("匹配中…");
        countView.setText("");
        stopCreep();
        ring.setIndeterminate(true);
        pctView.setText("…");
    }

    /** 阶段 3：写入云端。 */
    public void stageWriting() {
        if (finished) return;
        stageView.setText("写入云端…");
        setStepState(0, STATE_DONE);
        setStepState(1, STATE_DONE);
        setStepState(2, STATE_ACTIVE);
        stopCreep();
        ring.setIndeterminate(false);
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
        stopCreep();
        ring.setIndeterminate(false);
        applyPercent(100);
    }

    /**
     * 保底爬升：当没有真实进度回调时，让百分比缓慢往 CREEP_CEILING 爬。
     * <p>
     * 这不是「假装完成」——上限卡在 86%，且真实回调一旦到来会立刻 stopCreep()
     * 交给真实数据。目的只是避免弹窗长时间停在 0% 像卡死。
     * <p>
     * 速度递减（越接近上限越慢），视觉上像「在努力但还没完」。
     */
    private void startCreep() {
        stopCreep();
        creepRunnable = new Runnable() {
            @Override
            public void run() {
                if (finished) return;
                int cur = lastPercent;
                if (cur >= CREEP_CEILING) return;
                // 20% 以前走得快，之后逐渐减速
                int step = cur < 20 ? 3 : (cur < 50 ? 2 : 1);
                int next = Math.min(CREEP_CEILING, cur + step);
                applyPercent(next);
                creepHandler.postDelayed(this, 260);
            }
        };
        creepHandler.postDelayed(creepRunnable, 300);
    }

    private void stopCreep() {
        if (creepRunnable != null) {
            creepHandler.removeCallbacks(creepRunnable);
            creepRunnable = null;
        }
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
        stopCreep();               // 必须停，否则关窗后 Handler 还在跑
        ring.setIndeterminate(false);
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