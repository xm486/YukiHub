package com.yuki.yukihub.fvp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;

/**
 * FVP 引擎（rfvp）宿主 Activity。
 *
 * <p>宿主模型与上游一致：Java 持有 SurfaceView 生命周期与 Choreographer 主循环，
 * Rust 引擎经 {@code rfvp_android_*} C ABI 被逐帧驱动；引擎跑在独立进程 {@code :fvp}，
 * launchMode=singleInstance（游戏中再次启动不叠加实例）。
 *
 * <p>相对上游官方 App 的三处增强（对齐 Tyranor 的做法）：
 * <ol>
 *   <li>切后台回来用 {@code setSurface} 重挂窗口，<b>不重启本局</b>（官方 App 在
 *       surfaceDestroyed 里直接 finish，切出去一下游戏就没了）；</li>
 *   <li>启动期显示加载遮罩，避免黑屏干等；</li>
 *   <li>返回键转发 Windows VK_ESCAPE 给引擎（让游戏自己弹菜单），连按两次才退出。</li>
 * </ol>
 *
 * <p>继承 {@link Activity} 而非 AppCompatActivity：引擎界面是纯 SurfaceView，
 * 不需要 AppCompat 控件体系，也就不受「必须使用 AppCompat 主题」的限制
 * （manifest 里配的是 android:style 系全屏主题）。
 *
 * <p>引擎所需的参数全部来自 Intent extras（见下方 EXTRA_* 常量），由
 * {@code EmulatorLauncher.buildInternalFvpIntent} 下发。
 */
public final class FvpActivity extends Activity implements
        SurfaceHolder.Callback,
        Choreographer.FrameCallback,
        View.OnTouchListener {

    private static final String TAG = "FvpActivity";

    /** 游戏根目录（真实文件系统路径，rfvp 不支持 SAF）。 */
    public static final String EXTRA_GAME_ROOT = "fvpGameRoot";
    /** 文本编码：sjis / gbk / utf8。 */
    public static final String EXTRA_NLS = "fvpNls";
    /** 文本 HiDPI 渲染开关。 */
    public static final String EXTRA_TEXT_HIDPI = "fvpTextHidpi";
    /** 系统 CJK 字体回退开关。 */
    public static final String EXTRA_SYSTEM_FONT = "fvpSystemFont";
    /** 自定义字体文件绝对路径（空 = 不强制）。 */
    public static final String EXTRA_FONT_PATH = "fvpFontPath";
    /** 画面放大倍数（1.0 = 原样；渲染面等比放大并居中裁边，字随画面变大）。 */
    public static final String EXTRA_SCREEN_SCALE = "fvpScreenScale";
    /** 画面铺满：按游戏虚拟分辨率 1:1 渲染（buffer=虚拟尺寸），拉伸铺满屏幕。 */
    public static final String EXTRA_STRETCH_FILL = "fvpStretchFill";
    /** 全局文字缩放系数（1.0 = 脚本原大；需要 YukiHub 补丁版 librfvp.so）。 */
    public static final String EXTRA_TEXT_SCALE = "fvpTextScale";
    /** 行距跟随系数（1.0 = 与字号等比；0.5 = 放大增量减半；0.0 = 行距不变）。 */
    public static final String EXTRA_TEXT_LINE_SCALE = "fvpTextLineScale";

    /** 引擎侧取消/返回键（Windows VK）。 */
    private static final int VK_ESCAPE = 0x1B;

    /** 双击退出窗口：首次返回键转发 ESC 并提示，窗口内再按一次才真正退出。 */
    private static final long BACK_EXIT_WINDOW_MS = 2000L;

    /** 单帧最大步进，防止切后台回来时引擎收到一个巨大的 dt。 */
    private static final int MAX_FRAME_DT_MS = 250;

    private FrameLayout rootLayout;
    private SurfaceView surfaceView;
    private View loadingOverlay;

    private long handle;
    private boolean running;
    private boolean surfaceReady;
    private long lastFrameNs;
    private long lastBackMs;

    private String gameRoot;
    private String nls;
    private boolean textHidpi = true;
    private boolean systemFont = true;
    private String fontPath = "";
    private float screenScale = 1.0f;
    /** 全局文字缩放（引擎级；1.0 = 脚本原大）。 */
    private float textScale = 1.0f;
    /** 行距跟随系数（引擎级；只在文字放大时起作用，1.0 = 与字号等比）。 */
    private float lineScale = 0.0f;
    /** 铺满模式的渲染 buffer 尺寸（= 游戏虚拟分辨率）；0 = 未启用。 */
    private int bufW;
    private int bufH;

    /**
     * 待下发输入事件队列（每帧最多下发一个"边沿"）。
     *
     * <p>脚本侧实测（YHPROBE）：
     * <ul>
     *   <li>脚本**不用** {@code InputGetEvent}（探测 0 次），只靠
     *       {@code InputGetDown} / {@code InputGetUp} / {@code PrimHit}；</li>
     *   <li>脚本每帧先跑"推进对话"块（{@code InputGetDown}），**后**跑
     *       "按钮判定"块（{@code PrimHit}）；</li>
     *   <li>同一次物理点击若让 down 与 up 落在同一帧，脚本会在同一帧里
     *       同时看到两者 —— 表现就是**"点了按钮，对话也过一句"**（穿透），
     *       以及**点击直接跳下一句、没有"先补全文字"**（割裂感）。</li>
     * </ul>
     *
     * <p>因此这里把 down/up 全部排队，由 {@link #doFrame} 在 step() 之前
     * **每帧最多下发一个边沿**，从而保证：
     * <ol>
     *   <li>down 永远比 up 早至少一帧（脚本能先"补全文字"再"下一句"）；</li>
     *   <li>down 之前一定先发过 move（光标先到位，缓解按钮穿透）。</li>
     * </ol>
     * {@code NaN} 表示队列为空。
     */
    private double pendingDownX = Double.NaN;
    private double pendingDownY = Double.NaN;
    private double pendingUpX = Double.NaN;
    private double pendingUpY = Double.NaN;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // 必须先读参数再建布局：画面放大靠 SurfaceView 的布局尺寸实现，
        // 顺序反了会导致 scale 读到了却没参与布局（v1「放大无效果」就是这个原因）。
        Intent intent = getIntent();
        gameRoot = intent == null ? null : trimOrNull(intent.getStringExtra(EXTRA_GAME_ROOT));
        if (gameRoot == null) {
            Toast.makeText(this, "FVP 引擎启动失败：未获取到游戏目录", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        nls = intent.getStringExtra(EXTRA_NLS);
        textHidpi = intent.getBooleanExtra(EXTRA_TEXT_HIDPI, true);
        systemFont = intent.getBooleanExtra(EXTRA_SYSTEM_FONT, true);
        fontPath = trimOrEmpty(intent.getStringExtra(EXTRA_FONT_PATH));
        screenScale = FvpLaunchPrefs.normalizeScale(
                String.valueOf(intent.getFloatExtra(EXTRA_SCREEN_SCALE, 1.0f)));
        boolean stretchFill = intent.getBooleanExtra(EXTRA_STRETCH_FILL, false);
        textScale = FvpLaunchPrefs.normalizeScale(
                String.valueOf(intent.getFloatExtra(EXTRA_TEXT_SCALE, 1.0f)));
        lineScale = FvpLaunchPrefs.normalizeLineScale(
                String.valueOf(intent.getFloatExtra(EXTRA_TEXT_LINE_SCALE, 0.0f)));
        if (stretchFill) {
            int[] virtual = parseFvpVirtualSize(gameRoot);
            if (virtual != null) {
                bufW = virtual[0];
                bufH = virtual[1];
            } else {
                Toast.makeText(this, "未能识别游戏脚本分辨率，铺满模式未生效", Toast.LENGTH_LONG).show();
            }
        }

        setContentView(buildContentView());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 全屏游戏画面延伸进刘海/挖孔区（与 KRKR/Artemis 走同一套策略，避免画面被安全区挤偏）
        com.yuki.yukihub.util.CutoutCompat.setCutoutMode(getWindow(), true);
        surfaceView.getHolder().addCallback(this);
        if (bufW > 0 && bufH > 0) {
            // 铺满模式：buffer 固定为游戏虚拟分辨率，引擎 1:1 渲染（无黑边），
            // SurfaceView 再把 buffer 拉伸到全屏（系统合成器完成，等价 GameHub 全屏）。
            surfaceView.getHolder().setFixedSize(bufW, bufH);
        }
        surfaceView.setOnTouchListener(this);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.requestFocus();
        surfaceView.setKeepScreenOn(true);

        applyImmersive();
        Log.i(TAG, "onCreate root=" + gameRoot + " nls=" + FvpLaunchPrefs.normalizeNls(nls)
                + " hidpi=" + textHidpi + " systemFont=" + systemFont
                + " scale=" + screenScale
                + " stretch=" + (bufW > 0 ? bufW + "x" + bufH : "off")
                + " font=" + (fontPath.isEmpty() ? "-" : fontPath));
    }

    private View buildContentView() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        if (screenScale > 1.0f) {
            // 画面放大：渲染面等比放大并居中（四周裁边），字随画面变大。
            // 触摸经 onTouch 用 getX/getY（view 相对坐标）上报，放大后依然与画面一一对应。
            // Surface 尺寸随布局变大，引擎在 surfaceChanged 里自动 resize 到新分辨率。
            rootLayout.addView(surfaceView, new FrameLayout.LayoutParams(
                    (int) (getResources().getDisplayMetrics().widthPixels * screenScale),
                    (int) (getResources().getDisplayMetrics().heightPixels * screenScale),
                    Gravity.CENTER));
        } else {
            rootLayout.addView(surfaceView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        loadingOverlay = buildLoadingOverlay();
        rootLayout.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return rootLayout;
    }

    /** 启动遮罩：黑底 + 转圈 + 文案（引擎首帧渲染出来后移除）。 */
    private View buildLoadingOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.BLACK);

        ProgressBar spinner = new ProgressBar(this);
        overlay.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView label = new TextView(this);
        label.setText("正在启动 FVP 引擎…");
        label.setTextColor(0xFFB0B6C4);
        label.setTextSize(14.0f);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        labelParams.topMargin = (int) (72 * getResources().getDisplayMetrics().density);
        overlay.addView(label, labelParams);
        return overlay;
    }

    private void hideLoadingOverlay() {
        if (loadingOverlay != null && rootLayout != null) {
            try { rootLayout.removeView(loadingOverlay); } catch (Throwable ignored) { }
            loadingOverlay = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleInstance：游戏运行中再次收到启动请求时不叠加新实例。
        Toast.makeText(this, "已有 FVP 游戏正在运行", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        maybeStartFrameLoop();
    }

    @Override
    protected void onPause() {
        stopFrameLoop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopFrameLoop();
        destroyEngine();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private void applyImmersive() {
        WindowInsetsControllerCompat controller =
                ViewCompat.getWindowInsetsController(getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    // ---------- Surface ----------

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (handle != 0L) {
            // 后台恢复：Android 换了新的 ANativeWindow，重挂窗口即可，不重启本局。
            NativeRfvp.setSurface(handle, holder.getSurface(), surfaceWidth(holder), surfaceHeight(holder));
            surfaceReady = true;
            Log.i(TAG, "surface rebound after background");
        } else {
            ensureEngine(holder);
        }
        maybeStartFrameLoop();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (handle == 0L) {
            ensureEngine(holder);
        } else if (surfaceReady) {
            NativeRfvp.resize(handle, Math.max(1, width), Math.max(1, height));
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        // ANativeWindow 即将失效，但引擎必须存活：切后台不重启游戏，onDestroy 才销毁。
        surfaceReady = false;
        stopFrameLoop();
    }

    private int surfaceWidth(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        int width = frame != null ? frame.width() : surfaceView.getWidth();
        return Math.max(1, width);
    }

    private int surfaceHeight(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        int height = frame != null ? frame.height() : surfaceView.getHeight();
        return Math.max(1, height);
    }

    private void ensureEngine(@NonNull SurfaceHolder holder) {
        if (handle != 0L) return;

        // 必须先初始化 ndk-context，音频后端（AAudio/OpenSL）依赖它。
        NativeRfvp.initAndroidContext(getApplicationContext());

        int width = surfaceWidth(holder);
        int height = surfaceHeight(holder);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        double scale = metrics.density;

        long created = NativeRfvp.create(holder.getSurface(), width, height, scale,
                gameRoot, FvpLaunchPrefs.normalizeNls(nls));
        if (created == 0L) {
            Toast.makeText(this, "FVP 引擎初始化失败：引擎库缺失或游戏目录不可读", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        handle = created;
        surfaceReady = true;

        NativeRfvp.setTextHidpi(handle, textHidpi);
        NativeRfvp.setSystemFont(handle, systemFont);
        if (textScale > 1.0f) {
            // 引擎级全局文字缩放：仅 YukiHub 补丁版 librfvp.so 有该符号，
            // 旧版引擎没有时桥只打 warning，本调用安全无害。
            NativeRfvp.setTextScale(handle, textScale);
            // 行距跟随程度：只压行距步进，字形大小不受影响（0.5 = 放大增量减半）。
            NativeRfvp.setTextLineScale(handle, lineScale);
        }
        applyCustomFont();
        Log.i(TAG, "engine created: " + width + "x" + height + " nls=" + FvpLaunchPrefs.normalizeNls(nls)
                + " hidpi=" + textHidpi + " systemFont=" + systemFont
                + (textScale > 1.0f ? " textScale=" + textScale + " lineScale=" + lineScale : ""));
    }

    /** 强制自定义字体；文件不存在或加载失败时静默回退游戏默认字体。 */
    private void applyCustomFont() {
        if (handle == 0L) return;
        String path = fontPath;
        boolean fromAsset = false;
        if (path.isEmpty()) {
            // 内置默认字体（assets/fonts/snow.ttf）：CJK 全覆盖（含日文假名与常用汉字），
            // 对所有 FVP 游戏首次启动自动注入，日文文本同样正常渲染，缺简中字形的问题一并消除。
            path = extractBundledFont();
            fromAsset = true;
            if (path == null) return;
        }
        try {
            if (!new File(path).isFile()) {
                Log.w(TAG, "custom font missing, fall back: " + path);
                return;
            }
        } catch (Throwable ignored) {
            return;
        }
        int fontId = NativeRfvp.addFont(handle, path);
        if (fontId >= 0) {
            NativeRfvp.setForcedFont(handle, fontId);
            Log.i(TAG, (fromAsset ? "bundled" : "forced") + " font enabled: id=" + fontId + " path=" + path);
        } else {
            Log.w(TAG, "custom font load failed, fall back to game default: " + path);
        }
    }

    /** 把 assets 里的内置字体解到私有目录（assets 不能直接传路径给引擎）。 */
    private String extractBundledFont() {
        try {
            File dir = new File(getFilesDir(), "fvp_fonts");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "bundled_snow.ttf");
            if (out.isFile() && out.length() > 1024) return out.getAbsolutePath();
            try (java.io.InputStream in = getAssets().open("fonts/snow.ttf");
                 java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
            }
            Log.i(TAG, "bundled font extracted: " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "extract bundled font failed: " + t);
            return null;
        }
    }

    private void destroyEngine() {
        if (handle != 0L) {
            try { NativeRfvp.destroy(handle); } catch (Throwable ignored) { }
            handle = 0L;
        }
    }

    // ---------- 帧循环 ----------

    private void maybeStartFrameLoop() {
        if (!running && handle != 0L && surfaceReady) {
            running = true;
            lastFrameNs = 0L;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void stopFrameLoop() {
        if (running) {
            running = false;
            lastFrameNs = 0L;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running || handle == 0L || !surfaceReady) return;
        if (lastFrameNs == 0L) lastFrameNs = frameTimeNanos;
        long dtNs = frameTimeNanos - lastFrameNs;
        lastFrameNs = frameTimeNanos;

        int dtMs = (int) (dtNs / 1_000_000L);
        if (dtMs < 0) dtMs = 0;
        if (dtMs > MAX_FRAME_DT_MS) dtMs = MAX_FRAME_DT_MS;

        // 输入事件队列：每帧最多下发一个「边沿」，且 down 优先于 up。
        //
        // 为什么必须"每帧最多一个"：脚本每帧依次执行「推进对话」(InputGetDown) 与
        // 「按钮判定」(PrimHit)。若 down 与 up 落在同一帧（快速点击时极易发生），
        // 脚本会在同一帧同时看到"按下"和"抬起" —— 表现就是点了按钮对话也过一句、
        // 且点击直接跳下一句（没有"先补全文字"的层次感）。
        // 拆成两帧后：第 N 帧只有 down（脚本补全文字），第 N+1 帧才 up（再点一次才下一句）。
        if (!Double.isNaN(pendingDownX)) {
            double dx = pendingDownX;
            double dy = pendingDownY;
            pendingDownX = Double.NaN;
            pendingDownY = Double.NaN;
            NativeRfvp.touch(handle, 0, dx, dy);
        } else if (!Double.isNaN(pendingUpX)) {
            double ux = pendingUpX;
            double uy = pendingUpY;
            pendingUpX = Double.NaN;
            pendingUpY = Double.NaN;
            NativeRfvp.touch(handle, 2, ux, uy);
        }

        int status = NativeRfvp.step(handle, dtMs);
        if (status != 0) {
            finish();
            return;
        }
        hideLoadingOverlay();
        Choreographer.getInstance().postFrameCallback(this);
    }

    // ---------- 输入 ----------

    /**
     * 触摸事件映射：**move 直通 + down 延后一帧 + up 直通**。
     *
     * <p>要解决的问题：点右下角存档/菜单按钮时，**按钮响应了，对话也过一句**（点击穿透）。
     *
     * <p>根因（已在引擎源码确认）：脚本判定「点没点到按钮」用的是
     * {@code PrimHit} → {@code prim_hit(...)} → {@code inputs_manager.get_cursor_x/y()}，
     * 读的是**当前光标**（立即更新，非帧冻结）；而判定「推进对话」用的是
     * {@code InputGetDown(LeftClick)}，读的是**帧冻结**的 down 边沿。
     * Windows 上鼠标先在按钮上悬停若干帧，脚本在 down 之前的帧就已通过 PrimHit
     * 得知「光标在按钮上」，于是跳过对话推进；Android 手指落下几乎瞬移到位，
     * 若 move 与 down 同帧到达，脚本在 down 那一帧才第一次知道光标在按钮上，
     * 来不及跳过 → down 边沿与 PrimHit 同时成立 → 按钮和对话都响应。
     *
     * <p>因此必须让**光标状态比 down 早至少一帧**：ACTION_DOWN 只发 move，
     * down 登记到 {@link #pendingDownX}，由下一次 doFrame 在 step() 之前下发。
     *
     * <p>与历史两次尝试的区别：
     * <ul>
     *   <li>纯直通：move/down 同帧 → 穿透。</li>
     *   <li>同帧补 move（上一版）：引擎的 host_touch 本来就每次先调
     *       notify_mouse_move，补发是冗余的，仍同帧 → 穿透。</li>
     *   <li>UP 合成版（ff76e1c）：down 一直拖到**抬手**才合成，导致按下边沿
     *       丢失（滑条拖不动、退出不保存）。</li>
     *   <li>本方案：down 只延后**一帧**（落手后立刻补上），按下边沿完整保留，
     *       拖动轨迹由直通的 MOVE 传递，滑条与设置保存都正常。</li>
     * </ul>
     */
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (handle == 0L || event == null) return false;
        // 铺满模式下 buffer（=引擎坐标系）与 view 尺寸不同，需要按比例换算
        double x = event.getX();
        double y = event.getY();
        if (bufW > 0 && bufH > 0) {
            int viewW = Math.max(1, surfaceView.getWidth());
            int viewH = Math.max(1, surfaceView.getHeight());
            x = x * bufW / viewW;
            y = y * bufH / viewH;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 立即发 move（光标先到位），down 排队等下一帧。
                NativeRfvp.touch(handle, 1, x, y);
                pendingDownX = x;
                pendingDownY = y;
                return true;
            case MotionEvent.ACTION_MOVE:
                NativeRfvp.touch(handle, 1, x, y);
                return true;
            case MotionEvent.ACTION_UP:
                // up 也排队：保证 down 与 up 永远不在同一帧（否则脚本会在同一帧
                // 同时看到"按下"和"抬起" → 跳过打字与推进对话同时发生）。
                NativeRfvp.touch(handle, 1, x, y);
                pendingUpX = x;
                pendingUpY = y;
                return true;
            case MotionEvent.ACTION_CANCEL:
                pendingDownX = Double.NaN;
                pendingDownY = Double.NaN;
                pendingUpX = Double.NaN;
                pendingUpY = Double.NaN;
                NativeRfvp.touch(handle, 3, x, y);
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onBackPressed() {
        long now = SystemClock.uptimeMillis();
        if (lastBackMs != 0L && now - lastBackMs <= BACK_EXIT_WINDOW_MS) {
            super.onBackPressed();
            return;
        }
        lastBackMs = now;
        // 先让游戏自己处理（等价于按 Esc）：多数 Gal 会弹出系统菜单。
        NativeRfvp.keyEvent(handle, VK_ESCAPE, 0);
        NativeRfvp.keyEvent(handle, VK_ESCAPE, 1);
        Toast.makeText(this, "再按一次返回键退出游戏", Toast.LENGTH_SHORT).show();
    }

    // ---------- 工具 ----------

    private static String trimOrNull(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    // ---------- FVP 虚拟分辨率解析（铺满模式用） ----------

    /** game_mode → 分辨率表，与 rfvp script/parser.rs get_screen_size 一致。 */
    private static final int[][] FVP_MODE_TABLE = {
            {640, 480}, {800, 600}, {1024, 768}, {1280, 960}, {1600, 1200},
            {640, 480}, {1024, 576}, {1024, 640}, {1280, 720}, {1280, 800},
            {1440, 810}, {1440, 900}, {1680, 945}, {1680, 1050},
            {1920, 1080}, {1920, 1200},
    };

    /**
     * 从游戏目录的脚本头解析虚拟分辨率。
     *
     * hcb 头部布局（见 rfvp script/parser.rs）：偏移 0 为 sys_desc_offset(u32)，
     * sys_desc_offset+8 为 game_mode(u8)。脚本文件选择规则与引擎 find_hcb 一致：
     * 优先 *.bch，其次 *.hcb，各取字典序第一个。
     *
     * @return {宽, 高}；无法解析时返回 null
     */
    @Nullable
    private static int[] parseFvpVirtualSize(String gameRoot) {
        try {
            File dir = new File(gameRoot);
            File[] files = dir.listFiles();
            if (files == null) return null;
            File script = null;
            for (String ext : new String[]{".bch", ".hcb"}) {
                File first = null;
                for (File f : files) {
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!n.endsWith(ext)) continue;
                    if (first == null || f.getName().compareTo(first.getName()) < 0) first = f;
                }
                if (first != null) { script = first; break; }
            }
            if (script == null) return null;
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(script, "r")) {
                long len = raf.length();
                if (len < 16) return null;
                long sysDesc = readLeUint32(raf, 0);
                long modeOff = sysDesc + 8L;
                if (modeOff < 0 || modeOff >= len) return null;
                                int mode = readByteAt(raf, modeOff);
                if (mode < 0 || mode >= FVP_MODE_TABLE.length) return null;
                Log.i(TAG, "virtual size from " + script.getName()
                        + ": game_mode=" + mode
                        + " -> " + FVP_MODE_TABLE[mode][0] + "x" + FVP_MODE_TABLE[mode][1]);
                return new int[]{FVP_MODE_TABLE[mode][0], FVP_MODE_TABLE[mode][1]};
            }
        } catch (Throwable t) {
            Log.w(TAG, "parseFvpVirtualSize failed", t);
            return null;
        }
    }

    /** 小端读 u32。 */
    private static long readLeUint32(java.io.RandomAccessFile raf, long off) throws java.io.IOException {
        raf.seek(off);
        int b0 = raf.read(), b1 = raf.read(), b2 = raf.read(), b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new java.io.IOException("eof");
        return (long) b0 | ((long) b1 << 8) | ((long) b2 << 16) | ((long) b3 << 24);
    }

    /** 读指定偏移的一个字节。 */
    private static int readByteAt(java.io.RandomAccessFile raf, long off) throws java.io.IOException {
        raf.seek(off);
        return raf.read();
    }
}