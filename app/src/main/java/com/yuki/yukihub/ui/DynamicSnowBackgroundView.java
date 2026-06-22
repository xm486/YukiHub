package com.yuki.yukihub.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Lightweight animated winter/aurora background.
 * Drawn in code to avoid static dirty-looking snow dots and keep the UI readable.
 */
public class DynamicSnowBackgroundView extends View {
    private static final int PARTICLE_COUNT = 18;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random(486);
    private final SnowParticle[] particles = new SnowParticle[PARTICLE_COUNT];
    private long startTime;
    private LinearGradient baseGradient;
    private int cachedW;
    private int cachedH;

    // Dynamic theme colors
    private int baseColor1 = Color.rgb(11, 16, 40);
    private int baseColor2 = Color.rgb(22, 29, 70);
    private int baseColor3 = Color.rgb(54, 31, 92);
    private int auroraColor1 = 0x465AC8FA;
    private int auroraColor2 = 0x52AF52DE;
    private int auroraColor3 = 0x243C6CFF;

    public DynamicSnowBackgroundView(Context context) {
        super(context);
        init();
    }

    public DynamicSnowBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DynamicSnowBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        particlePaint.setStyle(Paint.Style.FILL);
        startTime = System.currentTimeMillis();
        for (int i = 0; i < particles.length; i++) {
            particles[i] = new SnowParticle();
            particles[i].seed(random);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cachedW = Math.max(1, w);
        cachedH = Math.max(1, h);
        baseGradient = new LinearGradient(
                0, 0, cachedW, cachedH,
                new int[]{baseColor1, baseColor2, baseColor3},
                new float[]{0f, 0.50f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float t = (System.currentTimeMillis() - startTime) / 1000f;

        paint.setShader(baseGradient);
        canvas.drawRect(0, 0, cachedW, cachedH, paint);
        paint.setShader(null);

        drawAuroraBlob(canvas,
                cachedW * (0.18f + 0.055f * sin(t * 0.13f)),
                cachedH * (0.18f + 0.045f * cos(t * 0.17f)),
                cachedW * 0.50f,
                auroraColor1,
                (0x00 << 24) | (auroraColor1 & 0x00FFFFFF));

        drawAuroraBlob(canvas,
                cachedW * (0.80f + 0.060f * sin(t * 0.10f + 2.1f)),
                cachedH * (0.76f + 0.050f * cos(t * 0.14f)),
                cachedW * 0.56f,
                auroraColor2,
                (0x00 << 24) | (auroraColor2 & 0x00FFFFFF));

        drawAuroraBlob(canvas,
                cachedW * (0.50f + 0.040f * sin(t * 0.08f + 4.0f)),
                cachedH * (0.44f + 0.030f * cos(t * 0.11f)),
                cachedW * 0.62f,
                auroraColor3,
                (0x00 << 24) | (auroraColor3 & 0x00FFFFFF));

        drawParticles(canvas, t);

        paint.setShader(null);
        paint.setColor(0x08030812);
        canvas.drawRect(0, 0, cachedW, cachedH, paint);

        postInvalidateOnAnimation();
    }

    private void drawAuroraBlob(Canvas canvas, float cx, float cy, float radius, int centerColor, int edgeColor) {
        paint.setShader(new RadialGradient(cx, cy, radius, centerColor, edgeColor, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
    }

    private void drawAuroraRibbon(Canvas canvas, float t) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(18f, cachedH * 0.035f));
        paint.setColor(0x086FB7D8);
        float y = cachedH * (0.24f + 0.025f * sin(t * 0.20f));
        for (int i = -1; i <= 1; i++) {
            float offset = i * cachedH * 0.055f;
            canvas.drawLine(-cachedW * 0.08f, y + offset, cachedW * 1.08f, y + cachedH * 0.12f + offset, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawParticles(Canvas canvas, float t) {
        for (SnowParticle p : particles) {
            float x = (p.x * cachedW + sin(t * p.swaySpeed + p.phase) * p.sway * cachedW) % cachedW;
            if (x < 0) x += cachedW;
            float y = (p.y * cachedH + t * p.speed * cachedH) % cachedH;
            int alpha = (int) (p.alpha * (0.55f + 0.45f * sin(t * p.twinkle + p.phase)));
            alpha = Math.max(10, Math.min(40, alpha));
            particlePaint.setColor((alpha << 24) | 0xDDEBFF);
            canvas.drawCircle(x, y, p.size, particlePaint);
        }
    }

    private float sin(float v) {
        return (float) Math.sin(v);
    }

    private float cos(float v) {
        return (float) Math.cos(v);
    }

    /** Set dynamic theme colors for base gradient and aurora blobs. */
    public void setThemeColors(int bg, int bg2, int auroraColor1, int auroraColor2, int auroraColor3) {
        this.baseColor1 = bg;
        this.baseColor2 = bg2;
        float[] hsv = new float[3];
        Color.colorToHSV(bg2, hsv);
        hsv[0] = (hsv[0] + 30f) % 360f;
        hsv[2] = Math.min(1f, hsv[2] * 1.3f);
        this.baseColor3 = Color.HSVToColor(hsv);
        this.auroraColor1 = auroraColor1;
        this.auroraColor2 = auroraColor2;
        this.auroraColor3 = auroraColor3;
        if (cachedW > 0 && cachedH > 0) {
            baseGradient = new LinearGradient(
                    0, 0, cachedW, cachedH,
                    new int[]{baseColor1, baseColor2, baseColor3},
                    new float[]{0f, 0.50f, 1f},
                    Shader.TileMode.CLAMP
            );
        }
    }

    private static class SnowParticle {
        float x;
        float y;
        float size;
        float speed;
        float sway;
        float swaySpeed;
        float twinkle;
        float phase;
        int alpha;

        void seed(Random r) {
            x = r.nextFloat();
            y = r.nextFloat();
            size = 0.45f + r.nextFloat() * 1.05f;
            speed = 0.006f + r.nextFloat() * 0.014f;
            sway = 0.006f + r.nextFloat() * 0.014f;
            swaySpeed = 0.18f + r.nextFloat() * 0.35f;
            twinkle = 0.45f + r.nextFloat() * 0.75f;
            phase = r.nextFloat() * 6.28318f;
            alpha = 20 + r.nextInt(28);
        }
    }
}
