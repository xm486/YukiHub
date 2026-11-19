package com.yuki.yukihub.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/**
 * 环形进度条（同步进度用）。
 * <p>
 * 为什么不用系统 ProgressDialog：它是白色小圈 + 一行字，与全站的深蓝玻璃风完全不搭，
 * 而且只能显示一句话，没法表达「读库 → 匹配 → 写入」的阶段。
 * <p>
 * 这里只负责画环，不负责动画——进度值由外部 setProgress() 驱动，
 * 由调用方用 ValueAnimator 或直接按真实回调推进，避免动画和真实进度打架。
 */
public class SyncRingView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    /** 0f ~ 1f，单调递增由调用方保证。 */
    private float progress = 0f;

    private int ringColor1 = 0xFF8AB4FF;
    private int ringColor2 = 0xFFFF8AB3;
    private int trackColor = 0x2A9AA4BF;

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

    /** 主题适配：让环的颜色跟随应用主色。 */
    public void setColors(int c1, int c2, int track) {
        this.ringColor1 = c1;
        this.ringColor2 = c2;
        this.trackColor = track;
        trackPaint.setColor(track);
        rebuildGradient();
        invalidate();
    }

    /** 0f ~ 1f。外部传非法值会被夹紧。 */
    public void setProgress(float p) {
        float v = p;
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;
        // 单调性保护：只允许前进，防止上游算错导致视觉回退
        if (v < progress) return;
        progress = v;
        invalidate();
    }

    /** 强制设置（用于开始新一轮时归零）。 */
    public void resetProgress() {
        progress = 0f;
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    private void rebuildGradient() {
        // 未测量前用默认尺寸，测量后会重建
        barPaint.setShader(null);
        invalidate();
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

        // 进度弧（从 12 点开始，顺时针）
        if (progress > 0f) {
            ensureShader(w, h);
            canvas.drawArc(oval, -90f, 360f * progress, false, barPaint);
        }
    }

    private float density = -1f;

    private float dp(float v) {
        if (density < 0f) density = getResources().getDisplayMetrics().density;
        return v * density;
    }
}