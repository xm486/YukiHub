package com.yuki.yukihub.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 环形进度条（同步进度用）。
 * <p>
 * 只负责画环，不负责业务进度。
 * <p>
 * <b>为什么要有动画层</b>：真实同步的进度回调往往只有两三个数据点
 * （例如 VNDB 全部命中缓存时只有 0/39 → 39/39），
 * 如果 setProgress 直接赋值，视觉上就是「0% 瞬间跳到 88% 然后消失」。
 * 所以这里把「目标值」和「当前绘制值」分开：
 * <ul>
 *   <li>targetProgress —— 业务给的真实目标，单调递增</li>
 *   <li>progress —— 当前画面上的值，由 ValueAnimator 平滑追赶 target</li>
 * </ul>
 * 数字显示走 target（诚实反映真实进度），环的弧度走动画（视觉平滑）。
 * <p>
 * 另外提供 {@link #setIndeterminate(boolean)} 不确定模式：
 * 当数据源压根没有进度回调时（例如 NextMoe 的 resolve），
 * 环会持续旋转，避免卡在 0% 让用户以为卡死。
 */
public class SyncRingView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    /** 当前<b>绘制</b>用的进度（动画驱动）。 */
    private float progress = 0f;
    /** 业务目标进度，单调递增。 */
    private float targetProgress = 0f;

    private int ringColor1 = 0xFF8AB4FF;
    private int ringColor2 = 0xFFFF8AB3;
    private int trackColor = 0x2A9AA4BF;

    private ValueAnimator animator;

    /** 不确定模式：环上跑一段固定弧长并旋转。 */
    private boolean indeterminate = false;
    private float indeterminateAngle = 0f;
    private ValueAnimator spinAnimator;

    public SyncRingView(Context context) {
        super(context);
        init();
    }

    public SyncRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SyncRingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeWidth(dp(6f));
        trackPaint.setColor(trackColor);

        barPaint.setStyle(Paint.Style.STROKE);
        barPaint.setStrokeCap(Paint.Cap.ROUND);
        barPaint.setStrokeWidth(dp(6f));
    }

    /** 主题适配。 */
    public void setColors(int c1, int c2, int track) {
        this.ringColor1 = c1;
        this.ringColor2 = c2;
        this.trackColor = track;
        trackPaint.setColor(track);
        barPaint.setShader(null);
        invalidate();
    }

    /**
     * 设置目标进度（带动画）。
     * <p>
     * 单调保护：小于当前 target 的请求会被忽略，回退不可能发生。
     *
     * @param p 0f ~ 1f
     */
    public void setProgress(float p) {
        float v = p;
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;
        if (v <= targetProgress) return;      // 只进不退
        targetProgress = v;
        animateTo(v);
    }

    /**
     * 立即跳到目标值（不带动画），用于新一轮开始时归零。
     * 归零必须走这个，否则动画反向跑会看起来在倒退。
     */
    public void resetProgress() {
        cancelAnimator();
        progress = 0f;
        targetProgress = 0f;
        invalidate();
    }

    /** 直接读取目标进度。 */
    public float getProgress() {
        return targetProgress;
    }

    /** 不确定模式（无进度回调时用，环持续旋转）。 */
    public void setIndeterminate(boolean on) {
        if (indeterminate == on) return;
        indeterminate = on;
        if (on) {
            cancelAnimator();
            startSpin();
        } else {
            stopSpin();
        }
        invalidate();
    }

    private void animateTo(float target) {
        cancelAnimator();
        if (indeterminate) return;   // 不确定模式下不追赶，交给 spin

        final float from = progress;
        final float delta = target - from;
        if (delta <= 0f) {
            progress = target;
            invalidate();
            return;
        }
        // 时长策略：差值越大走得越久，但单次上限 620ms，
        // 保证「0 → 88%」这种大步也能被肉眼看清，而不是瞬间跳过去。
        int duration = (int) Math.min(620, 180 + delta * 700);
        animator = ValueAnimator.ofFloat(from, target);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void startSpin() {
        stopSpin();
        spinAnimator = ValueAnimator.ofFloat(0f, 360f);
        spinAnimator.setDuration(1100);
        spinAnimator.setRepeatCount(ValueAnimator.INFINITE);
        spinAnimator.setInterpolator(null);
        spinAnimator.addUpdateListener(a -> {
            indeterminateAngle = (float) a.getAnimatedValue();
            invalidate();
        });
        spinAnimator.start();
    }

    private void stopSpin() {
        if (spinAnimator != null) {
            spinAnimator.cancel();
            spinAnimator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 必须清理，否则 View 被销毁后动画还在跑（内存泄漏 + 无谓 invalidate）
        cancelAnimator();
        stopSpin();
    }

    private void ensureShader(int w, int h) {
        if (barPaint.getShader() != null) return;
        SweepGradient g = new SweepGradient(w / 2f, h / 2f,
                new int[]{ringColor1, ringColor2, ringColor1},
                new float[]{0f, 0.55f, 1f});
        barPaint.setShader(g);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float stroke = dp(6f);
        float pad = stroke / 2f + dp(1f);
        float size = Math.min(w, h) - pad * 2f;
        if (size <= 0) return;

        oval.set(pad, pad, pad + size, pad + size);

        // 轨道
        canvas.drawArc(oval, 0f, 360f, false, trackPaint);

        ensureShader(w, h);

        if (indeterminate) {
            // 不确定模式：一段 70° 的弧绕圈跑
            canvas.drawArc(oval, indeterminateAngle - 90f, 70f, false, barPaint);
            return;
        }

        if (progress > 0f) {
            canvas.drawArc(oval, -90f, 360f * progress, false, barPaint);
        }
    }

    private float density = -1f;

    private float dp(float v) {
        if (density < 0f) density = getResources().getDisplayMetrics().density;
        return v * density;
    }
}