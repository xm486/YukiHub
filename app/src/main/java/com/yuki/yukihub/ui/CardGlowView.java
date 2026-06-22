package com.yuki.yukihub.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/** Lightweight rotating border glow for selected game cards. */
public class CardGlowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private long startTime;
    private SweepGradient gradient;
    private int color1 = 0x5AC8FA;
    private int color2 = 0xAF52DE;

    public CardGlowView(Context context) {
        super(context);
        init();
    }

    public CardGlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CardGlowView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        startTime = System.currentTimeMillis();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildGradient(w, h);
    }

    private void rebuildGradient(int w, int h) {
        if (w <= 0 || h <= 0) return;
        int c1 = color1;
        int c2 = color2;
        gradient = new SweepGradient(
                w / 2f,
                h / 2f,
                new int[]{
                        (0x00 << 24) | (c1 & 0x00FFFFFF),
                        (0x44 << 24) | (c1 & 0x00FFFFFF),
                        0xDDCFE8FF,
                        (0x66 << 24) | (c2 & 0x00FFFFFF),
                        (0x00 << 24) | (c1 & 0x00FFFFFF)
                },
                new float[]{0f, 0.22f, 0.36f, 0.55f, 1f}
        );
    }

    /** Set dynamic glow colors from theme extraction. */
    public void setThemeColors(int glowColor1, int glowColor2) {
        this.color1 = glowColor1 & 0x00FFFFFF;
        this.color2 = glowColor2 & 0x00FFFFFF;
        if (getWidth() > 0 && getHeight() > 0) {
            rebuildGradient(getWidth(), getHeight());
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (gradient == null) return;
        float t = (System.currentTimeMillis() - startTime) / 1000f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        matrix.setRotate((t * 72f) % 360f, cx, cy);
        gradient.setLocalMatrix(matrix);
        paint.setShader(gradient);
        rect.set(dp(2), dp(2), getWidth() - dp(2), getHeight() - dp(2));
        canvas.drawRoundRect(rect, dp(12), dp(12), paint);
        paint.setShader(null);
        postInvalidateOnAnimation();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
