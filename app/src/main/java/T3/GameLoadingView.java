package T3;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * Polished launch overlay replacing the old black "Loading..." TextView.
 * Drawn entirely in code — no XML layout — because the view is
 * dynamically added to {@code KR2Activity.mFrameLayout}.
 */
public class GameLoadingView extends View {

    // YukiHub palette (from colors.xml)
    private static final int BG_TOP = 0xFF0B1020;
    private static final int BG_BOTTOM = 0xFF111936;
    private static final int PRIMARY = 0xFF8AB4FF;
    private static final int SECONDARY = 0xFFFF8AB3;
    private static final int TEXT_PRIMARY = 0xFFF5F7FF;
    private static final int TEXT_MUTED = 0xFF9AA4BF;
    private static final int DANGER = 0xFFFF6B6B;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Animation state
    private float rotationAngle = 0f;
    private float dotPhase = 0f;
    private float shimmerX = 0f;
    private boolean errorMode = false;

    private final ValueAnimator rotateAnim;
    private final ValueAnimator dotAnim;
    private final ValueAnimator shimmerAnim;
    // Entrance
    private float entranceProgress = 0f;
    private final ValueAnimator entranceAnim;
    private final boolean skipEntrance;


    // Tip rotation — each launch starts at a different position
    public static final String EXTRA_CONTINUE_LOADING = "yukihubContinueLaunchLoading";
    public static final String EXTRA_INITIAL_TIP_INDEX = "yukihubLaunchTipIndex";

    private static final String[] TIPS = {
            "每一句台词，都是通往另一颗心的路。",
            "在像素交织的世界里，寻找属于你的那条线。",
            "有些感动只有亲身体验才会明白。",
            "Galgame 的魅力在于选择本身就是答案。",
            "一个好故事值得反复品味，就像值得反复重启的存档。",
            "你按下的是鼠标，改变的是命运。",
            "哪怕只有一条线分歧，结局也可能天差地别。",
            "画面会褪色，故事不会。",
            "在这个屏幕里，你是唯一的观众，也是主角。",
            "在此刻驻足，倾听角色的呼吸。",
            "BGM 响起的那一瞬，一切都对了。",
            "存档不是终点，而是可能性的记录。",
            "有些角色离开屏幕后依然住在你的记忆里。",
            "好的 Galgame 让你在通关后久久沉默。",
            "选择困难症？这大概就是 Galgame 的浪漫。",
            "CG 全收集了，但她的话还是那句最动人。",
            "不跳过任何一句独白，是对作者最大的尊重。",
            "每次多周目都是一次平行世界的旅行。",
            "比起抵达终点，途中的分歧更值得回味。",
            "你不仅仅是玩家，你是这段时光的一部分。",
            "保存进度，也是保存了那一刻的心情。",
            "文字值得被慢慢读完。",
            "陪伴是最长情的告白——角色对你的也是。",
            "有些伏笔三章前就埋下了，而你此刻才懂。",
            "别急，让片尾曲放完。",
    };
    private int tipIndex = 0;
    private long lastTipSwitch = 0L;
    private static final long TIP_INTERVAL = 3500L;

    public GameLoadingView(Context context) {
        this(context, false, -1);
    }

    public GameLoadingView(Context context, boolean skipEntrance) {
        this(context, skipEntrance, -1);
    }

    public GameLoadingView(Context context, boolean skipEntrance, int initialTipIndex) {
        super(context);
        this.skipEntrance = skipEntrance;
        density = context.getResources().getDisplayMetrics().density;
        if (skipEntrance) entranceProgress = 1f;

        bgPaint.setAntiAlias(true);
        logoPaint.setAntiAlias(true);
        logoPaint.setTextAlign(Paint.Align.CENTER);
        ringPaint.setAntiAlias(true);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setAntiAlias(true);
        dotPaint.setStyle(Paint.Style.FILL);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setAntiAlias(true);
        tipPaint.setTextAlign(Paint.Align.CENTER);

        // Start from a random tip so each launch feels different; when handed off from
        // MainActivity, reuse the same tip index to make the two Activity windows feel continuous.
        if (initialTipIndex >= 0) {
            tipIndex = initialTipIndex % TIPS.length;
        } else {
            tipIndex = new Random().nextInt(TIPS.length);
        }

        // Rotation animation (ring)
        rotateAnim = ValueAnimator.ofFloat(0f, 360f);
        rotateAnim.setDuration(1400L);
        rotateAnim.setRepeatCount(ValueAnimator.INFINITE);
        rotateAnim.setInterpolator(new LinearInterpolator());
        rotateAnim.addUpdateListener(a -> { rotationAngle = (float) a.getAnimatedValue(); invalidate(); });

        // Dot pulsing animation
        dotAnim = ValueAnimator.ofFloat(0f, 1f);
        dotAnim.setDuration(1600L);
        dotAnim.setRepeatCount(ValueAnimator.INFINITE);
        dotAnim.setInterpolator(new LinearInterpolator());
        dotAnim.addUpdateListener(a -> { dotPhase = (float) a.getAnimatedValue(); invalidate(); });

        // Shimmer sweep
        shimmerAnim = ValueAnimator.ofFloat(-1f, 2f);
        shimmerAnim.setDuration(2400L);
        shimmerAnim.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnim.setInterpolator(new LinearInterpolator());
        shimmerAnim.addUpdateListener(a -> { shimmerX = (float) a.getAnimatedValue(); invalidate(); });

        // Entrance animation
        entranceAnim = ValueAnimator.ofFloat(0f, 1f);
        entranceAnim.setDuration(700L);
        entranceAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        entranceAnim.addUpdateListener(a -> { entranceProgress = (float) a.getAnimatedValue(); invalidate(); });

        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        rotateAnim.start();
        dotAnim.start();
        shimmerAnim.start();
        if (!skipEntrance) entranceAnim.start();
        if (lastTipSwitch == 0L) lastTipSwitch = System.currentTimeMillis();
        handler.post(tipTicker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        rotateAnim.cancel();
        dotAnim.cancel();
        shimmerAnim.cancel();
        entranceAnim.cancel();
        handler.removeCallbacks(tipTicker);
    }

    private final Runnable tipTicker = new Runnable() {
        @Override
        public void run() {
            if (errorMode) return;
            long now = System.currentTimeMillis();
            if (now - lastTipSwitch > TIP_INTERVAL) {
                lastTipSwitch = now;
                tipIndex = (tipIndex + 1) % TIPS.length;
            }
            invalidate();
            handler.postDelayed(this, 80L);
        }
    };

    public int getTipIndex() {
        return tipIndex;
    }

    public void showError(String message) {
        errorMode = true;
        rotateAnim.cancel();
        dotAnim.cancel();
        shimmerAnim.cancel();
        handler.removeCallbacks(tipTicker);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float dp = density;

        // === Background gradient ===
        bgPaint.setShader(new LinearGradient(0, 0, 0, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP));
        bgPaint.setAlpha(errorMode ? 220 : 255);
        canvas.drawRect(0, 0, w, h, bgPaint);

        if (entranceProgress < 0.01f) return;

        // === Logo circle with rotating ring ===
        float ringRadius = 42 * dp;          // bigger ring
        float ringCenterY = cy - 70 * dp;    // move up more
        float entranceScale = 0.3f + 0.7f * entranceProgress;

        canvas.save();
        canvas.scale(entranceScale, entranceScale, cx, ringCenterY);
        canvas.translate(cx, ringCenterY);

        // Subtle glow behind ring
        Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(PRIMARY);
        glowPaint.setAlpha(12);
        canvas.drawCircle(0, 0, ringRadius + 8 * dp, glowPaint);

        // Rotating arc ring
        if (!errorMode) {
            ringPaint.setStrokeWidth(3f * dp);
            ringPaint.setShader(new LinearGradient(-ringRadius, 0, ringRadius, 0,
                    PRIMARY, SECONDARY, Shader.TileMode.CLAMP));
            ringPaint.setAlpha(210);
            canvas.drawArc(new RectF(-ringRadius, -ringRadius, ringRadius, ringRadius),
                    rotationAngle, 130f, false, ringPaint);

            // Second smaller ring (opposite spin)
            ringPaint.setStrokeWidth(1.5f * dp);
            ringPaint.setAlpha(70);
            canvas.drawArc(new RectF(-ringRadius, -ringRadius, ringRadius, ringRadius),
                    -rotationAngle * 0.7f + 180f, 55f, false, ringPaint);

            // Three pulsing dots on ring
            for (int i = 0; i < 3; i++) {
                float angle = rotationAngle * 0.5f + i * 120f;
                float rad = (float) Math.toRadians(angle);
                float dx = (float) Math.cos(rad) * ringRadius;
                float dy = (float) Math.sin(rad) * ringRadius;
                float dotR = (2f + 2f * (float) Math.sin(dotPhase * Math.PI * 2 + i * 2f)) * dp;
                if (dotR < 0) dotR = 0;
                dotPaint.setColor(i == 0 ? PRIMARY : (i == 1 ? SECONDARY : 0xFFFFFFFF));
                dotPaint.setAlpha(210);
                canvas.drawCircle(dx, dy, Math.max(dotR, 1.5f * dp), dotPaint);
            }
        } else {
            // Error state: static ring
            ringPaint.setStrokeWidth(3f * dp);
            ringPaint.setColor(DANGER);
            ringPaint.setAlpha(130);
            ringPaint.setShader(null);
            canvas.drawArc(new RectF(-ringRadius, -ringRadius, ringRadius, ringRadius),
                    0, 360, false, ringPaint);
        }

        // Logo letter "Y" in center
        float logoSize = entranceProgress < 1f
                ? (28 * dp * (0.5f + 0.5f * entranceProgress))
                : (28 * dp);
        logoPaint.setTextSize(logoSize);
        logoPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        logoPaint.setColor(errorMode ? DANGER : TEXT_PRIMARY);
        logoPaint.setAlpha((int)(255 * entranceProgress));

        canvas.drawText("Y", 0, logoSize / 3f, logoPaint);
        // Shimmer on logo
        if (!errorMode && shimmerX >= 0 && shimmerX <= 1) {
            float sx = (shimmerX - 0.5f) * ringRadius * 3f;
            LinearGradient shimmerShader = new LinearGradient(
                    sx - 24 * dp, 0, sx + 24 * dp, 0,
                    new int[]{0x00FFFFFF, 0xDDFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shimmerPaint.setShader(shimmerShader);
            shimmerPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
            canvas.drawText("Y", 0, logoSize / 3f, shimmerPaint);
        }

        canvas.restore();

        // === App name ===
        float nameY = ringCenterY + ringRadius + 32 * dp;
        textPaint.setTextSize(18 * dp);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setColor(errorMode ? DANGER : TEXT_PRIMARY);
        textPaint.setAlpha((int)(255 * entranceProgress));
        canvas.drawText(errorMode ? "启动失败" : "YukiHub", cx, nameY, textPaint);

        // === Status line ===
        float statusY = nameY + 24 * dp;
        tipPaint.setTextSize(11 * dp);
        tipPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        tipPaint.setColor(errorMode ? DANGER : TEXT_MUTED);
        tipPaint.setAlpha((int)(200 * entranceProgress));
        canvas.drawText(errorMode ? "游戏引擎初始化失败，请检查路径或重启" : "正在启动游戏…", cx, statusY, tipPaint);

        // === Tip quotes (italic, wrapped) ===
        if (!errorMode) {
            float quoteY = statusY + 30 * dp;
            tipPaint.setTextSize(11.5f * dp);
            tipPaint.setColor(TEXT_MUTED);
            tipPaint.setAlpha((int)(160 * entranceProgress));
            tipPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
            tipPaint.setTextAlign(Paint.Align.CENTER);
            breakAndDrawText(canvas, TIPS[tipIndex], cx, quoteY, w * 0.72f, 16 * dp);
            tipPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        }

        // === Bottom watermark ===
        Paint brandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        brandPaint.setTextAlign(Paint.Align.CENTER);
        brandPaint.setTextSize(9 * dp);
        brandPaint.setColor(TEXT_MUTED);
        brandPaint.setAlpha((int)(70 * entranceProgress));
        brandPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        canvas.drawText("YukiHub · Kirikiroid2 Engine", cx, h - 28 * dp, brandPaint);
    }

    /**
     * Breaks CJK text into lines fitting maxWidth and draws centered.
     */
    private void breakAndDrawText(Canvas canvas, String text, float centerX, float topY, float maxWidth, float lineSpacing) {
        if (text == null || text.isEmpty()) return;
        tipPaint.setTextAlign(Paint.Align.CENTER);
        float lineH = tipPaint.getTextSize() + lineSpacing * 0.3f;
        StringBuilder line = new StringBuilder();
        float y = topY;
        int drawnLines = 0;
        for (int i = 0; i < text.length() && drawnLines < 3; i++) {
            char ch = text.charAt(i);
            float testWidth = tipPaint.measureText(line.toString() + ch);
            if (testWidth > maxWidth && line.length() > 0) {
                canvas.drawText(line.toString(), centerX, y, tipPaint);
                y += lineH;
                drawnLines++;
                line.setLength(0);
            }
            line.append(ch);
        }
        if (line.length() > 0 && drawnLines < 3) {
            canvas.drawText(line.toString(), centerX, y, tipPaint);
        }
    }
}