package com.yuki.yukihub.translate;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.yuki.yukihub.HomeActivity;
import com.yuki.yukihub.R;

/**
 * YukiHub 悬浮翻译基础服务。
 *
 * 本阶段只负责悬浮球、拖动、点击/长按菜单和生命周期清理；
 * 截图、框选、OCR 与翻译在后续阶段接入。
 *
 * 适配说明：本类参考 MoeTranslate 的 FloatingBallService 思路，已转换为 Java，
 * 后续复制/修改相关代码时需保留原项目版权和 LGPL 声明。
 */
public class YukiTranslateService extends Service {
    private static final String TAG = "YukiTranslateService";
    private static final String CHANNEL_ID = "yukihub_translation";
    private static final int NOTIFICATION_ID = 10087;
    private static final long DEFAULT_LONG_PRESS_MS = 500L;
    private static final int DEFAULT_BALL_SIZE_DP = 52;

    public static final String ACTION_START = "com.yuki.yukihub.translate.START";
    public static final String ACTION_STOP = "com.yuki.yukihub.translate.STOP";

    /** 悬浮球状态 */
    private enum BallStatus {
        NORMAL,       // 正常状态：点击触发截图翻译
        CROP,         // 框选区域中
        MOVING_RESULT // 移动结果层中
    }

    private final Handler mainHandler = new Handler();
    private WindowManager windowManager;
    private TextView floatingBall;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams cropParams;
    private TranslateCropView cropView;
    private RectF selectedRect;
    private Point selectedOffset;
    private Runnable longPressRunnable;
    private OcrProvider ocrProvider;
    private TranslationManager translationManager;
    private TranslateResultOverlay resultOverlay;
    private BallStatus ballStatus = BallStatus.NORMAL;
    private boolean ballAdded;
    // 旋转动画（翻译中光效）
    private ValueAnimator rotationAnimator;
    private long longPressMs = DEFAULT_LONG_PRESS_MS;
    private boolean downMoved;
    private float downRawX;
    private float downRawY;
    private int downBallX;
    private int downBallY;

    // 翻译配置（从 TranslationPreferences 加载）
    private TranslationPreferences translatePrefs;
    private String sourceLanguage = "ja";
    private String targetLanguage = "zh";
    private int ocrMergeMode = 2;
    private int showSourceMode = TranslateResultOverlay.MODE_TRANSLATED_ONLY;

    // 自动翻译配置
    private boolean autoTranslating = false;
    private String lastOcrResult = "";
    private final Handler autoTranslateHandler = new Handler();
    private Runnable autoTranslateRunnable;
    private long autoTranslateInterval = 3000L;
    private int autoTranslateLengthThreshold = 10;
    private double autoTranslateSimilarityThreshold = 0.8;
    private int savedOrientation = 1;

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(context.getApplicationContext());
    }

    public static void start(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!canDrawOverlays(app)) {
            Toast.makeText(app, "请先允许 YukiHub 在其他应用上层显示", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(app, YukiTranslateService.class);
            intent.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
            Toast.makeText(app, "无法启动翻译悬浮服务", Toast.LENGTH_SHORT).show();
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        try {
            context.getApplicationContext().stopService(
                    new Intent(context.getApplicationContext(), YukiTranslateService.class));
        } catch (Throwable t) {
            Log.w(TAG, "stop failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // OCR 引擎在读取偏好后创建：当前固定使用 PaddleOCR。
        ocrProvider = null;
        // TranslationTextProvider 暂为 null，阶段 7 接入具体翻译引擎后替换
        translationManager = new TranslationManager(null);
        resultOverlay = new TranslateResultOverlay(this, windowManager, overlayWindowType());
        resultOverlay.setOnPositionChangedListener((x, y) -> {
            android.content.SharedPreferences prefs = getSharedPreferences("yukihub_prefs", MODE_PRIVATE);
            prefs.edit()
                    .putInt("translate_result_x", x)
                    .putInt("translate_result_y", y)
                    .apply();
        });
        loadTranslatePrefs();
        createNotificationChannel();
        savedOrientation = getResources().getConfiguration().orientation;
        autoTranslateRunnable = new Runnable() {
            @Override
            public void run() {
                if (autoTranslating && ballStatus == BallStatus.NORMAL) {
                    performAutoTranslate();
                    autoTranslateHandler.postDelayed(this, autoTranslateInterval);
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_STOP.equals(intent == null ? null : intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限未开启，翻译服务未启动", Toast.LENGTH_SHORT).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            addFloatingBallIfNeeded();
        } catch (Throwable t) {
            Log.e(TAG, "service initialization failed", t);
            cleanupOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAutoTranslate();
        releaseOcr();
        releaseTranslation();
        cleanupOverlay();
        // 清除翻译历史
        TranslationHistory.getInstance().clear();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void addFloatingBallIfNeeded() {
        if (ballAdded || windowManager == null) return;

        // 读取个性化设置
        int ballSize = getSharedPreferences("yukihub_prefs", MODE_PRIVATE)
                .getInt(TranslationPreferences.KEY_BALL_SIZE, TranslationPreferences.DEFAULT_BALL_SIZE);
        float ballOpacity = getSharedPreferences("yukihub_prefs", MODE_PRIVATE)
                .getFloat(TranslationPreferences.KEY_BALL_OPACITY, TranslationPreferences.DEFAULT_BALL_OPACITY);
        long longPressDelay = getSharedPreferences("yukihub_prefs", MODE_PRIVATE)
                .getLong(TranslationPreferences.KEY_LONG_PRESS_DELAY, TranslationPreferences.DEFAULT_LONG_PRESS_DELAY);
        longPressMs = longPressDelay;

        floatingBall = new TextView(this);
        floatingBall.setText("译");
        floatingBall.setTextColor(Color.WHITE);
        floatingBall.setTextSize(20);
        floatingBall.setGravity(Gravity.CENTER);
        floatingBall.setContentDescription("YukiHub 翻译悬浮球");
        floatingBall.setBackground(createBallBackground(ballOpacity));

        // 悬浮球图标：自定义优先
        String customIcon = getSharedPreferences("yukihub_prefs", MODE_PRIVATE)
                .getString(TranslationPreferences.KEY_CUSTOM_BALL_ICON, "");
        if (!customIcon.isEmpty()) {
            try {
                java.io.File iconFile = new java.io.File(getExternalFilesDir(null), "icon/" + customIcon);
                if (iconFile.exists()) {
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(iconFile.getAbsolutePath());
                    if (bitmap != null) {
                        // 圆形裁剪
                        android.graphics.Bitmap rounded = getCircularBitmap(bitmap);
                        if (rounded != null) {
                            android.widget.ImageView iv = new android.widget.ImageView(this);
                            iv.setImageBitmap(rounded);
                            // 用 ImageView 代替 TextView
                            // 由于 floatingBall 是 TextView，这里改用 setCompoundDrawables
                            android.graphics.drawable.Drawable d = new android.graphics.drawable.BitmapDrawable(getResources(), rounded);
                            floatingBall.setCompoundDrawablesWithIntrinsicBounds(null, null, null, d);
                            floatingBall.setText("");
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "load custom ball icon failed", t);
            }
        }

        floatingBall.setOnTouchListener(this::onBallTouch);

        ballParams = new WindowManager.LayoutParams(
                dp(ballSize), dp(ballSize),
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;
        android.content.SharedPreferences prefs = getSharedPreferences("yukihub_prefs", MODE_PRIVATE);
        ballParams.x = prefs.getInt("translate_ball_x", dp(24));
        ballParams.y = prefs.getInt("translate_ball_y", dp(220));
        windowManager.addView(floatingBall, ballParams);
        ballAdded = true;
    }

    /** 圆形裁剪 Bitmap */
    private android.graphics.Bitmap getCircularBitmap(android.graphics.Bitmap src) {
        if (src == null) return null;
        int size = Math.min(src.getWidth(), src.getHeight());
        android.graphics.Bitmap output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(output);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        android.graphics.Rect rect = new android.graphics.Rect(0, 0, size, size);
        android.graphics.RectF rectF = new android.graphics.RectF(rect);
        canvas.drawARGB(0, 0, 0, 0);
        canvas.drawOval(rectF, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, rect, rect, paint);
        return output;
    }

    private boolean onBallTouch(View view, MotionEvent event) {
        if (ballParams == null || windowManager == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downMoved = false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downBallX = ballParams.x;
                downBallY = ballParams.y;
                longPressRunnable = () -> {
                    if (!downMoved) showBallMenu();
                };
                mainHandler.postDelayed(longPressRunnable, longPressMs);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                    downMoved = true;
                    cancelLongPress();
                    ballParams.x = downBallX + Math.round(dx);
                    ballParams.y = downBallY + Math.round(dy);
                    try {
                        windowManager.updateViewLayout(floatingBall, ballParams);
                    } catch (Throwable t) {
                        Log.w(TAG, "update ball position failed", t);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                boolean clicked = !downMoved;
                cancelLongPress();
                if (clicked) onBallClick();
                saveBallPosition();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelLongPress();
                return true;
            default:
                return true;
        }
    }

    private void onBallClick() {
        switch (ballStatus) {
            case NORMAL:
                if (!ScreenshotServiceManager.isReady()) {
                    Toast.makeText(this, "请先在系统设置中开启 YukiHub 无障碍截图服务", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (selectedRect == null) {
                    // 首次使用：先弹引导，再进入框选
                    showFirstCropGuide();
                    return;
                }
                // 确保结果层已显示
                ensureResultOverlay();
                if (translationManager.isTranslating()) {
                    Toast.makeText(this, "翻译中，请稍候", Toast.LENGTH_SHORT).show();
                    return;
                }
                requestScreenshot(selectedRect, selectedOffset == null ? new Point(0, 0) : selectedOffset);
                break;
            case CROP:
                // 框选状态下点击悬浮球：确认选区
                if (cropView != null && cropView.getRect() != null) {
                    selectedRect = new RectF(cropView.getRect());
                    selectedOffset = cropView.getScreenOffset();
                }
                removeCropOverlay();
                ballStatus = BallStatus.NORMAL;
                if (!hideAdjustToast()) Toast.makeText(this, "框选完成", Toast.LENGTH_SHORT).show();
                break;
            case MOVING_RESULT:
                // 结果移动状态下点击悬浮球：关闭触摸
                if (resultOverlay != null) resultOverlay.setTouchable(false);
                ballStatus = BallStatus.NORMAL;
                break;
        }
    }

    private void showFirstCropGuide() {
        if (isFinishingDialogContextUnavailable()) {
            showCropOverlay();
            return;
        }
        try {
            AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("欢迎使用 YukiHub 翻译")
                    .setMessage("看起来你是第一次使用翻译功能～\n\n"
                            + "点击「开始框选」后，屏幕会进入区域选择模式：\n"
                            + "• 拖动四个角调整框选范围\n"
                            + "• 拖动中间移动整个选框\n"
                            + "• 再次点击悬浮球确认选区\n\n"
                            + "之后每次点击悬浮球，都会自动翻译框选区域内的文字。\n"
                            + "长按悬浮球可以打开菜单，进行重新框选、移动结果层等操作。")
                    .setPositiveButton("开始框选", (d2, w) -> showCropOverlay())
                    .setNegativeButton("稍后再说", null)
                    .setCancelable(true)
                    .create();
            Window window = d.getWindow();
            if (window != null) window.setType(overlayWindowType());
            d.show();
        } catch (Throwable t) {
            Log.w(TAG, "show guide failed", t);
            showCropOverlay();
        }
    }

    private void showCropOverlay() {
        if (cropView != null || windowManager == null) return;
        ballStatus = BallStatus.CROP;
        cropView = new TranslateCropView(this, null);
        cropParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        cropParams.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(cropView, cropParams);
            cropView.setRect(selectedRect);
            // 等框选层完成添加后再重排窗口顺序，确保悬浮球是真正最后添加的窗口。
            mainHandler.post(this::bringBallToFront);
        } catch (Throwable t) {
            Log.w(TAG, "add crop overlay failed", t);
            cropView = null;
            Toast.makeText(this, "无法打开区域选择层", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestScreenshot(RectF rect, Point offset) {
        ScreenshotServiceManager.takeScreenshot(rect, offset,
                new YukiScreenshotAccessibilityService.Callback() {
                    @Override
                    public void onSuccess(android.graphics.Bitmap bitmap) {
                        if (bitmap == null) {
                            Toast.makeText(YukiTranslateService.this, "截图数据为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        processOcrTranslation(bitmap);
                    }

                    @Override
                    public void onFailure(String message) {
                        Toast.makeText(YukiTranslateService.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /**
     * OCR 文本翻译模式：截图 → OCR → 文本翻译
     */
    private void processOcrTranslation(android.graphics.Bitmap bitmap) {
        if (ocrProvider == null) {
            ScreenshotServiceManager.recycle(bitmap);
            if (!autoTranslating && resultOverlay != null) {
                resultOverlay.setText("OCR 引擎未初始化");
            }
            return;
        }
        ocrProvider.recognize(bitmap, sourceLanguage, ocrMergeMode,
                new OcrProvider.Callback() {
                    @Override
                    public void onSuccess(String text, String lang) {
                        ScreenshotServiceManager.recycle(bitmap);
                        if (text == null || text.trim().isEmpty()) {
                            if (!autoTranslating && resultOverlay != null) {
                                resultOverlay.setText("未识别到文字");
                            }
                            return;
                        }
                        // 自动翻译模式：相似度判断
                        if (autoTranslating) {
                            if (!shouldTranslateText(text)) {
                                return;
                            }
                            lastOcrResult = text;
                        }
                        // 发送翻译请求
                        startBallSpinAnimation();
                        translationManager.translate(text, sourceLanguage, targetLanguage,
                                new TranslationManager.Callback() {
                                    @Override
                                    public void onSuccess(String sourceText, String translatedText) {
                                        stopBallSpinAnimation();
                                        if (resultOverlay != null) {
                                            resultOverlay.setResult(sourceText, translatedText, showSourceMode);
                                        }
                                    }

                                    @Override
                                    public void onError(Throwable error) {
                                        stopBallSpinAnimation();
                                        if (resultOverlay != null) {
                                            resultOverlay.setText("翻译失败：" + (error == null ? "未知错误" : error.getMessage()));
                                        }
                                    }
                                });
                    }

                    @Override
                    public void onFailure(Exception error) {
                        ScreenshotServiceManager.recycle(bitmap);
                        if (!autoTranslating && resultOverlay != null) {
                            resultOverlay.setText("OCR 失败：" + (error == null ? "未知错误" : error.getMessage()));
                        }
                    }
                });
    }

    /**
     * 根据用户设置创建文本翻译引擎实例。
     * 返回 null 表示引擎未配置。
     *
     * 支持：BING、NIUTRANS、UNIAI、VOLC、AZURE、DEEPL、BAIDU、TENCENT、CUSTOM
     */
    private TranslationTextProvider createTextProvider() {
        if (translatePrefs == null) return null;
        String engine = translatePrefs.getTextEngine();
        if ("BING".equals(engine)) {
            return new BingTranslationProvider();
        }
        if ("NIUTRANS".equals(engine)) {
            String apiKey = translatePrefs.raw().getString(TranslationPreferences.KEY_NIUTRANS_APIKEY, "");
            if (apiKey.isEmpty()) return null;
            return new NiuTransProvider(apiKey);
        }
        if ("UNIAI".equals(engine)) {
            String apiKey = translatePrefs.raw().getString(TranslationPreferences.KEY_OPENAI_APIKEY, "");
            String baseUrl = translatePrefs.raw().getString(TranslationPreferences.KEY_OPENAI_BASEURL, "");
            String model = translatePrefs.raw().getString(TranslationPreferences.KEY_OPENAI_MODEL, "");
            String systemPrompt = translatePrefs.raw().getString(TranslationPreferences.KEY_OPENAI_SYSTEM_PROMPT, "");
            String userPrompt = translatePrefs.raw().getString(TranslationPreferences.KEY_OPENAI_USER_PROMPT, "");
            String temperature = translatePrefs.raw().getString(TranslationPreferences.KEY_OPENAI_TEMPERATURE, "");
            String extraParamsJson = translatePrefs.raw().getString(TranslationPreferences.KEY_OPENAI_EXTRA_PARAMS, "");
            if (apiKey.isEmpty()) return null;
            java.util.List<OpenAiTranslationProvider.Pair> extraParams =
                    OpenAiTranslationProvider.decodeExtraParams(extraParamsJson);
            return new OpenAiTranslationProvider(apiKey, baseUrl, model,
                    systemPrompt, userPrompt, temperature, extraParams);
        }
        if ("VOLC".equals(engine)) {
            String ak = translatePrefs.raw().getString(TranslationPreferences.KEY_VOLC_AK, "");
            String sk = translatePrefs.raw().getString(TranslationPreferences.KEY_VOLC_SK, "");
            if (ak.isEmpty() || sk.isEmpty()) return null;
            return new VolcTranslationProvider(ak, sk);
        }
        if ("AZURE".equals(engine)) {
            String key = translatePrefs.raw().getString(TranslationPreferences.KEY_AZURE_KEY, "");
            if (key.isEmpty()) return null;
            return new AzureTranslationProvider(key);
        }
        if ("DEEPL".equals(engine)) {
            String host = translatePrefs.raw().getString(TranslationPreferences.KEY_DEEPL_HOST, "api-free.deepl.com");
            String apiKey = translatePrefs.raw().getString(TranslationPreferences.KEY_DEEPL_APIKEY, "");
            if (apiKey.isEmpty()) return null;
            return new DeepLProvider(host, apiKey);
        }
        if ("BAIDU".equals(engine)) {
            String appId = translatePrefs.raw().getString(TranslationPreferences.KEY_BAIDU_APPID, "");
            String secretKey = translatePrefs.raw().getString(TranslationPreferences.KEY_BAIDU_SECRETKEY, "");
            if (appId.isEmpty() || secretKey.isEmpty()) return null;
            return new BaiduTranslationProvider(appId, secretKey);
        }
        if ("TENCENT".equals(engine)) {
            String secretId = translatePrefs.raw().getString(TranslationPreferences.KEY_TENCENT_SECRETID, "");
            String secretKey = translatePrefs.raw().getString(TranslationPreferences.KEY_TENCENT_SECRETKEY, "");
            if (secretId.isEmpty() || secretKey.isEmpty()) return null;
            return new TencentTranslationProvider(secretId, secretKey);
        }
        if ("CUSTOM".equals(engine)) {
            int slot = translatePrefs.getCustomTextApiSlot();
            CustomApiConfig.TextConfig config = CustomApiConfig.loadTextConfig(translatePrefs.raw(), slot);
            if (config == null) {
                return null;
            }
            return new CustomTextApiProvider(config);
        }
        return null;
    }

    private void releaseOcr() {
        if (ocrProvider != null) {
            ocrProvider.release();
            ocrProvider = null;
        }
    }

    private void releaseTranslation() {
        if (translationManager != null) {
            translationManager.release();
        }
        if (resultOverlay != null) {
            resultOverlay.hide();
        }
    }

    /**
     * 确保结果悬浮层已添加到窗口，并保持悬浮球在最上层。
     */
    private void ensureResultOverlay() {
        if (resultOverlay == null || windowManager == null) return;
        if (!resultOverlay.isAdded()) {
            android.content.SharedPreferences prefs = getSharedPreferences("yukihub_prefs", MODE_PRIVATE);
            int x = prefs.getInt("translate_result_x", 0);
            int y = prefs.getInt("translate_result_y", 0);
            resultOverlay.show(x, y);
            resultOverlay.setTouchable(false);
            // 保持悬浮球在最上层
            bringBallToFront();
        }
    }

    /**
     * 将悬浮球移到最上层（先移除再添加）。
     */
    private void bringBallToFront() {
        if (floatingBall != null && ballAdded && windowManager != null && ballParams != null) {
            try {
                windowManager.removeView(floatingBall);
                windowManager.addView(floatingBall, ballParams);
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== 自动翻译 ====================

    /**
     * 从 TranslationPreferences 加载所有翻译配置。
     */
    private void loadTranslatePrefs() {
        translatePrefs = new TranslationPreferences(this);
        sourceLanguage = translatePrefs.getSourceLanguage();
        // PaddleOCR 字典不含韩文；旧配置若残留 ko，统一回退为日语。
        if ("ko".equalsIgnoreCase(sourceLanguage)) {
            sourceLanguage = "ja";
            translatePrefs.putString(TranslationPreferences.KEY_SOURCE_LANG, sourceLanguage);
        }
        targetLanguage = translatePrefs.getTargetLanguage();
        ocrMergeMode = translatePrefs.getOcrMergeMode();
        showSourceMode = translatePrefs.getShowSourceMode();
        autoTranslateInterval = translatePrefs.getAutoInterval();
        autoTranslateLengthThreshold = translatePrefs.getAutoLengthThreshold();
        autoTranslateSimilarityThreshold = translatePrefs.getAutoSimilarityThreshold();

        // OCR 引擎固定为 PaddleOCR，兼容旧版本 SharedPreferences 中的引擎值。
        translatePrefs.putString(TranslationPreferences.KEY_OCR_ENGINE,
                TranslationPreferences.DEFAULT_OCR_ENGINE);
        releaseOcr();
        ocrProvider = new PaddleOcrProvider(this);

        // 创建翻译引擎实例
        TranslationTextProvider textProvider = createTextProvider();
        if (textProvider != null) {
            translationManager.setProvider(textProvider);
        }

        // 应用结果层外观
        if (resultOverlay != null) {
            int fontColor = translatePrefs.raw().getInt(TranslationPreferences.KEY_RESULT_FONT_COLOR,
                    TranslationPreferences.DEFAULT_FONT_COLOR);
            int bgColor = translatePrefs.raw().getInt(TranslationPreferences.KEY_RESULT_BG_COLOR,
                    TranslationPreferences.DEFAULT_BG_COLOR);
            int padding = translatePrefs.raw().getInt(TranslationPreferences.KEY_RESULT_PADDING,
                    TranslationPreferences.DEFAULT_PADDING);
            resultOverlay.setAppearance(
                    translatePrefs.getResultFontSize(),
                    fontColor,
                    bgColor,
                    12f,
                    (float) padding,
                    translatePrefs.isResultPenetrable()
            );
        }
    }

    /**
     * 切换自动翻译开关。
     */
    private void toggleAutoTranslate() {
        if (autoTranslating) {
            stopAutoTranslate();
        } else {
            startAutoTranslate();
        }
    }

    /**
     * 启动自动翻译。
     */
    private void startAutoTranslate() {
        if (!ScreenshotServiceManager.isReady()) {
            Toast.makeText(this, "请先开启无障碍截图服务", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ballStatus != BallStatus.NORMAL) {
            Toast.makeText(this, "请先完成当前操作", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedRect == null) {
            Toast.makeText(this, "请先框选翻译区域", Toast.LENGTH_SHORT).show();
            return;
        }

        // 确保结果层已显示
        ensureResultOverlay();

        autoTranslating = true;
        lastOcrResult = "";
        savedOrientation = getResources().getConfiguration().orientation;
        autoTranslateHandler.post(autoTranslateRunnable);
        Toast.makeText(this, "自动翻译已开启", Toast.LENGTH_SHORT).show();
    }

    /**
     * 停止自动翻译。
     */
    private void stopAutoTranslate() {
        if (!autoTranslating) return;
        autoTranslating = false;
        autoTranslateHandler.removeCallbacks(autoTranslateRunnable);
        Toast.makeText(this, "自动翻译已关闭", Toast.LENGTH_SHORT).show();
    }

    /**
     * 执行一次自动翻译截图。
     */
    private void performAutoTranslate() {
        // 屏幕方向变化时停止
        if (savedOrientation != getResources().getConfiguration().orientation) {
            Toast.makeText(this, "屏幕方向已变化，自动翻译已停止", Toast.LENGTH_SHORT).show();
            stopAutoTranslate();
            return;
        }
        // 翻译中跳过
        if (translationManager.isTranslating()) {
            return;
        }
        // 发起截图
        requestScreenshot(selectedRect, selectedOffset == null ? new Point(0, 0) : selectedOffset);
    }

    /**
     * 判断是否需要翻译当前文本（自动翻译模式下使用）。
     *
     * @param currentText 当前 OCR 结果
     * @return true 需要翻译， false 跳过
     */
    private boolean shouldTranslateText(String currentText) {
        if (currentText == null || currentText.trim().isEmpty()) {
            return false;
        }
        // 短文本直接翻译
        if (currentText.length() < autoTranslateLengthThreshold) {
            return true;
        }
        // 首次翻译
        if (lastOcrResult.isEmpty()) {
            return true;
        }
        // 相似度判断
        double similarity = TranslationSimilarity.calculateSimilarity(lastOcrResult, currentText);
        return similarity < autoTranslateSimilarityThreshold;
    }

    private void removeCropOverlay() {
        if (cropView != null && windowManager != null) {
            try {
                windowManager.removeView(cropView);
            } catch (Throwable ignored) {
            }
        }
        cropView = null;
        cropParams = null;
    }

    private void showBallMenu() {
        if (isFinishingDialogContextUnavailable()) return;
        try {
            String[] menuItems = autoTranslating
                    ? new String[]{"重新框选区域", "移动结果层", "隐藏/显示结果层", "关闭自动翻译", "停止悬浮翻译"}
                    : new String[]{"重新框选区域", "移动结果层", "隐藏/显示结果层", "开启自动翻译", "停止悬浮翻译"};
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("YukiHub 翻译")
                    .setItems(menuItems,
                            (d, which) -> {
                                if (which == 0) {
                                    // 重新框选：保留 selectedRect，让新框选层从上次区域开始。
                                    showCropOverlay();
                                } else if (which == 1) {
                                    // 移动结果层
                                    if (resultOverlay != null && resultOverlay.isAdded()) {
                                        resultOverlay.setTouchable(true);
                                        ballStatus = BallStatus.MOVING_RESULT;
                                        if (!hideAdjustToast()) Toast.makeText(this, "拖动结果层后点击悬浮球确认", Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(this, "结果层未显示", Toast.LENGTH_SHORT).show();
                                    }
                                } else if (which == 2) {
                                    // 隐藏/显示结果层
                                    if (resultOverlay != null && resultOverlay.isAdded()) {
                                        resultOverlay.hide();
                                        Toast.makeText(this, "结果层已隐藏", Toast.LENGTH_SHORT).show();
                                    } else if (resultOverlay != null) {
                                        ensureResultOverlay();
                                        Toast.makeText(this, "结果层已显示", Toast.LENGTH_SHORT).show();
                                    }
                                } else if (which == 3) {
                                    // 自动翻译开关
                                    toggleAutoTranslate();
                                } else if (which == 4) {
                                    stopSelf();
                                }
                            })
                    .setNegativeButton("取消", null)
                    .create();
            Window window = dialog.getWindow();
            if (window != null) window.setType(overlayWindowType());
            dialog.show();
        } catch (Throwable t) {
            Log.w(TAG, "show menu failed", t);
            Toast.makeText(this, "无法打开翻译菜单", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isFinishingDialogContextUnavailable() {
        return !canDrawOverlays(this);
    }

    /** 是否隐藏操作提示 Toast（框选完成、移动结果层等） */
    private boolean hideAdjustToast() {
        return getSharedPreferences("yukihub_prefs", MODE_PRIVATE)
                .getBoolean(TranslationPreferences.KEY_ADJUST_NOT_TOAST,
                        TranslationPreferences.DEFAULT_ADJUST_NOT_TOAST);
    }

    private void cleanupOverlay() {
        stopBallSpinAnimation();
        cancelLongPress();
        removeCropOverlay();
        if (resultOverlay != null) {
            resultOverlay.hide();
        }
        if (floatingBall != null && windowManager != null && ballAdded) {
            try {
                windowManager.removeView(floatingBall);
            } catch (Throwable ignored) {
            }
        }
        floatingBall = null;
        ballAdded = false;
        saveBallPosition();
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) {
            mainHandler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private void saveBallPosition() {
        if (ballParams == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences("yukihub_prefs", MODE_PRIVATE);
        prefs.edit()
                .putInt("translate_ball_x", ballParams.x)
                .putInt("translate_ball_y", ballParams.y)
                .apply();
    }

    // ==================== 悬浮球旋转光效 ====================

    /** 启动翻译中旋转光效：悬浮球外围有光晕旋转呼吸 */
    private void startBallSpinAnimation() {
        if (rotationAnimator != null) return;
        if (floatingBall == null) return;

        rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
        rotationAnimator.setDuration(1200); // 1.2秒一圈
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.setInterpolator(null); // 线性匀速
        rotationAnimator.addUpdateListener(anim -> {
            if (floatingBall == null) return;
            float angle = (float) anim.getAnimatedValue();
            // 在悬浮球外围画一个旋转亮环
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            float op = getSharedPreferences("yukihub_prefs", MODE_PRIVATE)
                    .getFloat(TranslationPreferences.KEY_BALL_OPACITY, TranslationPreferences.DEFAULT_BALL_OPACITY);
            int baseColor = 0xEE6750A4;
            int alpha = Math.round(((baseColor >>> 24) & 0xFF) * op);
            bg.setColor((alpha << 24) | (baseColor & 0x00FFFFFF));
            // 添加一个渐变边框作为光效环
            int size = floatingBall.getWidth();
            if (size <= 0) size = dp(DEFAULT_BALL_SIZE_DP);
            int ringWidth = dp(2);
            int ringAlpha = (int) (70 + 50 * Math.sin(Math.toRadians(angle * 2))); // 亮度呼吸效果
            bg.setStroke(ringWidth, 0x40FFFFFF | (ringAlpha << 24));
            floatingBall.setBackground(bg);
        });
        rotationAnimator.start();
    }

    /** 停止旋转动画，恢复正常外观 */
    private void stopBallSpinAnimation() {
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
            rotationAnimator = null;
        }
        if (floatingBall != null) {
            float op = getSharedPreferences("yukihub_prefs", MODE_PRIVATE)
                    .getFloat(TranslationPreferences.KEY_BALL_OPACITY, TranslationPreferences.DEFAULT_BALL_OPACITY);
            floatingBall.setBackground(createBallBackground(op));
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, HomeActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 10087, intent, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("YukiHub 翻译")
                .setContentText("翻译悬浮球运行中")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "翻译悬浮窗", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("YukiHub 游戏悬浮翻译服务");
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private GradientDrawable createBallBackground() {
        return createBallBackground(1.0f);
    }

    private GradientDrawable createBallBackground(float opacity) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        int baseColor = 0xEE6750A4;
        int alpha = Math.round(((baseColor >>> 24) & 0xFF) * opacity);
        drawable.setColor((alpha << 24) | (baseColor & 0x00FFFFFF));
        drawable.setStroke(dp(1), 0xFFFFFFFF);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
