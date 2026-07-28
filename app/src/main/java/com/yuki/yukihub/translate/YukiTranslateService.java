package com.yuki.yukihub.translate;

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
    private static final long LONG_PRESS_MS = 500L;
    private static final int BALL_SIZE_DP = 52;

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

        floatingBall = new TextView(this);
        floatingBall.setText("译");
        floatingBall.setTextColor(Color.WHITE);
        floatingBall.setTextSize(20);
        floatingBall.setGravity(Gravity.CENTER);
        floatingBall.setContentDescription("YukiHub 翻译悬浮球");
        floatingBall.setBackground(createBallBackground());
        floatingBall.setOnTouchListener(this::onBallTouch);

        ballParams = new WindowManager.LayoutParams(
                dp(BALL_SIZE_DP), dp(BALL_SIZE_DP),
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
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
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
                    showCropOverlay();
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
                Toast.makeText(this, "框选完成", Toast.LENGTH_SHORT).show();
                break;
            case MOVING_RESULT:
                // 结果移动状态下点击悬浮球：关闭触摸
                if (resultOverlay != null) resultOverlay.setTouchable(false);
                ballStatus = BallStatus.NORMAL;
                break;
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
                        translationManager.translate(text, sourceLanguage, targetLanguage,
                                new TranslationManager.Callback() {
                                    @Override
                                    public void onSuccess(String sourceText, String translatedText) {
                                        if (resultOverlay != null) {
                                            resultOverlay.setResult(sourceText, translatedText, showSourceMode);
                                        }
                                    }

                                    @Override
                                    public void onError(Throwable error) {
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
            String model = translatePrefs.raw().getString(TranslationPreferences.KEY_OPENAI_MODEL, "gpt-3.5-turbo");
            if (apiKey.isEmpty()) return null;
            return new OpenAiTranslationProvider(apiKey, baseUrl, model, null, null, null);
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
            resultOverlay.setAppearance(
                    translatePrefs.getResultFontSize(),
                    0xFFE9A0B1,
                    0xD9383838,
                    12f,
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
                                        Toast.makeText(this, "拖动结果层后点击悬浮球确认", Toast.LENGTH_SHORT).show();
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

    private void cleanupOverlay() {
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
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(0xEE6750A4);
        drawable.setStroke(dp(1), 0xFFFFFFFF);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
