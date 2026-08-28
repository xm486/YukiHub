package com.yuri.onscripter;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

import com.yuki.yukihub.ons.OnsButtonConfig;
import com.yuki.yukihub.ons.OnsButtonRenderer;
import com.yuki.yukihub.ons.OnsLibLoader;
import com.yuki.yukihub.ons.OnsSettings;
import com.yuki.yukihub.ons.OnsVideoOverlay;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class ONScripter extends SDLActivity {
    private static final String TAG = "YukiONS";
    // 引擎版本不再硬编码：实际加载哪个版本由 OnsLibLoader 决定（用户可选，
    // 加载失败会自动回退）。写死会导致 getMainSharedObject() 指向未被释放的
    // 目录，SDL 找不到 main so 直接崩。
    private ArrayList<String> onsArgs;
    private boolean ignoreCutout = true;
    private String gameRoot;
    private FrameLayout onsOverlay;
    /** 视频覆盖层，播片时盖在 SDL 画面上，播完移除。 */
    private OnsVideoOverlay videoOverlay;
    /** 虚拟按键布局配置，来自引擎设置里的编辑器。 */
    private OnsButtonConfig btnConfig;
    private TextView toggleButton;
    /**
     * toggle 模式按钮的高亮状态：id -> 是否开启。
     * 用 Map 而不是单个 boolean，是因为可能有多个 toggle 类按钮
     * （AUTO / HIDE / FULL / 自定义），各自独立。
     */
    private final java.util.Map<String, Boolean> toggleStates = new java.util.HashMap<>();
    /** id -> 按钮视图，用于状态变化时刷新外观。 */
    private final java.util.Map<String, TextView> buttonViews = new java.util.HashMap<>();
    private boolean controlsVisible = true;
    /** 上次算按钮坐标时的 overlay 尺寸，用于识别真正的尺寸变化。 */
    private int lastLayoutW;
    private int lastLayoutH;
    private native int nativeInitJavaCallbacks();
    private native int nativeGetWidth();
    private native int nativeGetHeight();


    // ==================== getFD 缺失路径缓存 ====================
    //
    // 背景：引擎启动和运行期会反复探测一批本来就不存在的可选资源
    // （uoncur.bmp / cursor0.bmp / system.lua / 各种 default 素材……）。
    // 每次探测都会走 native 的 stat_ons -> getFD，而 stat_ons 在上游实现里
    // （onsyuri/onscripter_main.cpp:175）有两个问题：
    //   1. 每次调用都 new jbyte[strlen(path)]，若 delete[] 被外部 patch 掉就是泄漏；
    //   2. getFD 返回 -1 时仍直接 fstat(-1) / close(-1)，缺少防护。
    // 我们改不了 native，但可以让「注定失败的探测」不再反复穿透到那条路径上。
    //
    // 同时这里还省掉了 resolveGameFile() 里的 findFileIgnoreCase()——
    // 它在文件不存在时会 listFiles() 整个父目录，是本地磁盘 IO 的主要开销。
    //
    // 正确性约束（缓存只记录「读取时确认不存在」的路径）：
    //   - 只缓存 mode == 0（只读）的失败结果；写模式永不查缓存。
    //   - 写模式成功创建文件后，必须移除该路径的记录。
    //   - mkdir 成功后整体清空：新目录可能让此前不可解析的相对路径变得有效。
    private static final int MISSING_CACHE_LIMIT = 4096;
    private final java.util.Set<String> missingReadPaths =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    /** 命中计数，仅用于在退出时输出一条效果日志，不参与逻辑。 */
    private int missingCacheHits;

    @Override public void loadLibraries() {
        OnsLibLoader.load(this);
    }

    @Override public String[] getLibraries() {
        // 实际的 so 加载由 loadLibraries() -> OnsLibLoader 完成（需要按版本目录用
        // System.load 加载绝对路径，SDL 默认的 System.loadLibrary 走不通）。
        // 基类只在 loadLibraries() 和 getMainSharedObject() 里消费本方法，
        // 而这两个都已被覆写，所以这里仅用于声明依赖顺序，不参与真实加载。
        return new String[]{"SDL2", "lua", "jpeg", "bz2", "SDL2_image", "SDL2_mixer", "SDL2_ttf", "onsyuri"};
    }

    @Override public String getMainSharedObject() {
        // 必须用实际加载成功的版本，不能用编译期常量：
        // OnsLibLoader 在加载失败时会自动回退到旧版本目录。
        String version = OnsLibLoader.getActiveVersion(this);
        return new File(getFilesDir(), "libs/" + version + "/libonsyuri.so").getAbsolutePath();
    }

    @Override public String[] getArguments() {
        if (onsArgs == null) onsArgs = new ArrayList<>();
        return onsArgs.toArray(new String[0]);
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        gameRoot = firstNonEmpty(
                getIntent().getStringExtra("path"),
                getIntent().getStringExtra("gamePath"),
                getIntent().getStringExtra("rootUri"),
                getIntent().getStringExtra(OnsSettings.EXTRA_GAME_URI));
        gameRoot = normalizeRootPath(gameRoot);
        onsArgs = getIntent().getStringArrayListExtra(OnsSettings.EXTRA_GAME_ARGS);
        OnsSettings settings = OnsSettings.load(this);
        if (onsArgs == null) onsArgs = settings.buildArgs(this, gameRoot);
        ignoreCutout = getIntent().getBooleanExtra(OnsSettings.EXTRA_IGNORE_CUTOUT, settings.ignoreCutout);
        // 提前加载/释放 assets：确保 libonsyuri 与内置 DroidSansFallback.ttf 在 SDLActivity 启动前可用。
        OnsLibLoader.load(this);
        ensureDefaultFont();
        super.onCreate(savedInstanceState);
        fixSurfaceCentering();  // 修复平板设备上画面不居中的问题
        try { nativeInitJavaCallbacks(); } catch (Throwable t) { Log.w(TAG, "nativeInitJavaCallbacks failed", t); }
        setupVirtualControls();
        fullscreen();
    }

    @Override public void onResume() {
        super.onResume();
        fullscreen();
        reloadButtonsIfChanged();
    }

    /**
     * 回到前台时检查布局配置有没有变过（用户可能刚在设置里改完），变了就重建按钮。
     * 比较 JSON 字符串比逐字段比对简单，且配置量很小，开销可忽略。
     */
    private void reloadButtonsIfChanged() {
        if (onsOverlay == null) return;
        try {
            OnsButtonConfig latest = OnsButtonConfig.load(this);
            String now = latest.toJson().toString();
            if (btnConfig != null && now.equals(btnConfig.toJson().toString())) return;
            btnConfig = latest;
            // 布局换了，已开启的 toggle 高亮状态不再对应，清掉避免显示与引擎不一致
            toggleStates.clear();
            // 强制重建：尺寸没变但配置变了，不能被 lastLayout 短路掉
            lastLayoutW = 0;
            lastLayoutH = 0;
            buildControlsFromConfig();
            Log.i(TAG, "virtual buttons reloaded after config change");
        } catch (Throwable t) {
            Log.w(TAG, "reloadButtonsIfChanged failed", t);
        }
    }

    @Override public void onPause() {
        super.onPause();
        // 切后台时暂停解码：overlay 不随 Activity 销毁，回来还在。
        if (videoOverlay != null) videoOverlay.onHostPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) fullscreen();
        if (hasFocus && onsOverlay != null) {
            // 实测：SDL surface 的持续渲染会吞掉首次 invalidate，
            // 按钮要等窗口重新合成（切后台再回来）才显示。
            // 回到前台时强制刷一次，等价于用户手动切走再切回来。
            onsOverlay.post(() -> {
                if (onsOverlay != null) {
                    onsOverlay.requestLayout();
                    onsOverlay.invalidate();
                }
            });
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // 播片期间按键优先给视频层：任意键跳过，且不能把 ESC 透传给游戏
        // （否则会在播片时误触发菜单）。音量键仍交还系统。
        if (event != null && videoOverlay != null && videoOverlay.isPlaying()) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_UP
                    || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE
                    || code == KeyEvent.KEYCODE_MUTE) {
                return super.dispatchKeyEvent(event);
            }
            if (event.getAction() == KeyEvent.ACTION_UP) videoOverlay.skipByKey();
            return true;
        }
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        Log.d(TAG, "send ESC to ONS");
        try {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
        } catch (Throwable t) {
            Log.w(TAG, "send ESC failed", t);
        }
    }

    public int getFD(byte[] pathbyte, int mode) {
        try {
            if (pathbyte == null || gameRoot == null || gameRoot.isEmpty()) return -1;
            String raw = decodePath(pathbyte);
            boolean readOnly = (mode == 0);

            // 只读探测且此前已确认不存在 → 直接返回，不再触碰文件系统。
            if (readOnly && missingReadPaths.contains(raw)) {
                reportCacheHit();
                return -1;
            }

            File file = resolveGameFile(raw);
            if (file == null) {
                if (readOnly) rememberMissing(raw);
                return -1;
            }

            if (readOnly) {
                // 提前判存在性：ParcelFileDescriptor.open 对不存在的文件会抛
                // FileNotFoundException，构造异常本身在高频探测下开销可观，
                // 而这条路径每局游戏会走几百次。
                if (!file.isFile()) {
                    rememberMissing(raw);
                    return -1;
                }
            } else {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
            }

            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, readOnly
                    ? ParcelFileDescriptor.MODE_READ_ONLY
                    : (ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE));
            int fd = pfd.detachFd();
            // 写入会让原本「不存在」的路径变为存在，必须撤销缓存，
            // 否则后续读取会被错误地判定为缺失。
            if (!readOnly) missingReadPaths.remove(raw);
            Log.i(TAG, "getFD fd=" + fd + " mode=" + mode + " file=" + file);
            return fd;
        } catch (Throwable t) {
            // 只读失败按缺失记录：这里绝大多数是 ENOENT。
            // 用 debug 级别避免刷屏（原先每次探测都打一条 warn + 完整堆栈，
            // 启动阶段能刷出上百条，把真正有用的日志顶掉）。
            if (mode == 0 && pathbyte != null) {
                try { rememberMissing(decodePath(pathbyte)); } catch (Throwable ignored) { }
            }
            Log.d(TAG, "getFD failed mode=" + mode + ": " + t);
            return -1;
        }
    }

    /**
     * 记录一个只读探测确认缺失的路径。
     * 加上限是防止脚本用随机 / 递增文件名探测时无界增长；
     * 超限直接清空而不做 LRU —— 这里只是省 IO，重建成本很低，
     * 引入淘汰逻辑反而增加出错面。
     */
    private void rememberMissing(String raw) {
        if (raw == null || raw.isEmpty()) return;
        if (isVolatilePath(raw)) return;
        if (missingReadPaths.size() >= MISSING_CACHE_LIMIT) {
            Log.i(TAG, "missing-path cache full, reset (" + missingReadPaths.size() + ")");
            missingReadPaths.clear();
        }
        missingReadPaths.add(raw);
    }

    /**
     * 周期性输出缓存效果。
     *
     * 不放在 onDestroy 里统计：游戏运行在独立的 :ons 进程，退出时进程通常被
     * 直接杀掉，onDestroy 不保证执行（实测日志里确实看不到）。
     * 改为每命中若干次打一条，保证一定能观察到效果。
     */
    private void reportCacheHit() {
        missingCacheHits++;
        if (missingCacheHits % 200 == 0) {
            Log.i(TAG, "missing-path cache: " + missingCacheHits + " hits avoided, "
                    + missingReadPaths.size() + " paths cached");
        }
    }

    /**
     * 判断路径是否属于「存在性会变化且影响游戏语义」的类别，这类一律不缓存。
     *
     * 存档相关文件（引擎源码 ONScripter_file.cpp / ONScripter_command.cpp 里的
     * save%d.dat、gloval.sav、envdata）必须排除：
     * 存档槽位是否为空直接决定读档界面的显示，而且用户可能在外部
     * 删除或导入存档文件。缓存「不存在」会让已有存档读不出来。
     *
     * 这类路径数量固定（十几个），不缓存对整体效果没有影响。
     */
    private boolean isVolatilePath(String raw) {
        String name = raw;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.toLowerCase(java.util.Locale.ROOT);
        if (name.equals("envdata") || name.equals("gloval.sav") || name.equals("global.sav")) return true;
        // save0.dat ~ save999.dat
        if (name.startsWith("save") && name.endsWith(".dat")) return true;
        // 部分脚本自定义的 .sav / .dat 存档
        return name.endsWith(".sav");
    }

    public int mkdir(byte[] pathbyte) {
        try {
            if (pathbyte == null || gameRoot == null || gameRoot.isEmpty()) return -1;
            File f = resolveGameFile(decodePath(pathbyte));
            boolean ok = f != null && (f.exists() || f.mkdirs());
            // 新建目录会改变路径解析结果（原先解析不到的相对路径可能变得有效），
            // 缓存整体失效最安全。mkdir 频率极低，代价可忽略。
            if (ok && !missingReadPaths.isEmpty()) missingReadPaths.clear();
            return ok ? 0 : -1;
        } catch (Throwable t) {
            Log.w(TAG, "mkdir failed", t);
            return -1;
        }
    }

    public void playVideo(byte[] pathbyte) {
        if (pathbyte == null) return;
        String path = decodePath(pathbyte);
        Log.i(TAG, "playVideo " + path);
        try {
            File file = resolveVideoFile(path);
            if (file == null || !file.exists()) {
                Log.w(TAG, "video not found: " + path);
                return;
            }
            final String real = file.getAbsolutePath();
            // native 侧从 SDL 线程调用本方法，视图操作必须切到主线程。
            runOnUiThread(() -> startVideoOverlay(real));
        } catch (Throwable t) {
            Log.e(TAG, "playVideo failed", t);
        }
    }

    /**
     * 在本 Activity 窗口内覆盖播放，不启动新 Activity。
     *
     * 为什么不用独立 Activity：本类是 singleInstance + 独立 taskAffinity，
     * 拉起别的 Activity 会引发 task 切换，实测导致 SDL 收到 onStop()，
     * 且视频结束后返回的是 MainActivity 而非游戏本身。
     */
    private void startVideoOverlay(String realPath) {
        if (isFinishing() || isDestroyed()) return;
        if (videoOverlay == null) videoOverlay = new OnsVideoOverlay(this);
        boolean ok = videoOverlay.play(realPath, true);
        if (!ok) Log.w(TAG, "overlay refused to play: " + realPath);
    }

    public void playVideo(Uri uri) {
        if (uri == null) return;
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            Log.w(TAG, "playVideo(uri) has no path: " + uri);
            return;
        }
        final String real = path;
        runOnUiThread(() -> startVideoOverlay(real));
    }

    public void testVideo() {
        File file = resolveVideoFile("test.mp4");
        if (file != null && file.exists()) playVideo(Uri.fromFile(file));
    }

    // ==================== 虚拟按键（配置驱动） ====================

    /**
     * 按 {@link OnsButtonConfig} 构建虚拟按键。
     *
     * 与旧实现的区别：
     * - 布局、尺寸、透明度、按钮种类全部来自用户配置，不再硬编码；
     * - 所有按钮统一直径（旧实现主按钮 60dp、收起键 48dp 且字号混乱）；
     * - toggle 类按钮状态按 id 独立存放，不会出现多个 AUTO 抢同一个标志；
     * - 收起键字符统一为 ✕ / ☰（旧实现初始用 × / ≡，切换后变 ✕ / ☰，字形会跳变）。
     */
    private void setupVirtualControls() {
        try {
            btnConfig = OnsButtonConfig.load(this);
            // 收起状态不做持久化：每次进游戏都显示按钮。
            // 之前持久化过，结果下次进来按钮全 GONE，而唯一恢复入口是个
            // 半透明小图标，看起来就像功能坏了。收起只在本次会话有效。
            controlsVisible = true;
            OnsButtonConfig.clearCollapsed(this);

            onsOverlay = new FrameLayout(this);
            onsOverlay.setClickable(false);
            onsOverlay.setFocusable(false);

            // SDL 的 content root（mLayout）是 RelativeLayout，必须传它能识别的
            // LayoutParams，否则会被 generateLayoutParams 兜底成 WRAP_CONTENT。
            addContentView(onsOverlay, new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT));

            // 保证 overlay 在兄弟节点里排最后（最上层）
            onsOverlay.bringToFront();

            // 尺寸变化时重算坐标。SDL 启动过程中窗口会 relayout 两次
            // （实测先 2161x959 再 2414x1080），只算一次会用到中间态的错尺寸。
            onsOverlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l == lastLayoutW && b - t == lastLayoutH) return;
                buildControlsFromConfig();
            });

            buildControlsFromConfig();
        } catch (Throwable t) {
            Log.w(TAG, "setupVirtualControls failed", t);
        }
    }

    /**
     * 依配置生成按钮与收起键：每个按钮按中心点百分比坐标自由定位。
     * 会先清空既有视图，便于配置变更后重建。
     *
     * 外观与定位统一走 {@link OnsButtonRenderer}，和布局编辑器用同一份代码，
     * 保证编辑器里看到的位置和大小就是这里的效果。
     */
    private void buildControlsFromConfig() {
        if (onsOverlay == null || btnConfig == null) return;
        onsOverlay.removeAllViews();
        buttonViews.clear();
        toggleButton = null;

        int pw = onsOverlay.getWidth();
        int ph = onsOverlay.getHeight();
        if (pw <= 0 || ph <= 0) {
            Log.i(TAG, "buildControls skipped: overlay size not ready");
            return;   // 尺寸未就绪，等 layout 回调再来
        }

        for (OnsButtonConfig.Item item : btnConfig.allItems()) {
            TextView button = OnsButtonRenderer.createButton(
                    this, btnConfig, item, isToggleOn(item.id));
            bindButtonBehavior(button, item);
            buttonViews.put(item.id, button);
            FrameLayout.LayoutParams lp = OnsButtonRenderer.params(
                    this, btnConfig.size, item.x, item.y, pw, ph);
            onsOverlay.addView(button, lp);
        }

        if (btnConfig.toggleEnabled) {
            toggleButton = OnsButtonRenderer.createToggle(this, btnConfig, !controlsVisible);
            bindToggleBehavior(toggleButton);
            onsOverlay.addView(toggleButton, OnsButtonRenderer.params(
                    this, btnConfig.toggleSize, btnConfig.toggleX, btnConfig.toggleY, pw, ph));
        }

        lastLayoutW = pw;
        lastLayoutH = ph;
        applyVirtualControlsVisibility();

        // SDL 的 SurfaceView 有独立渲染循环，持续向合成器推帧。
        // 实测发现：addView 触发的 invalidate 会被吞掉，按钮要等窗口
        // 重新合成（切后台再回来）才显示。所以构建后强制请求重绘，
        // 并延迟多刷几次确保合成器拿到内容层的帧。
        onsOverlay.invalidate();
        onsOverlay.post(() -> {
            if (onsOverlay != null) {
                onsOverlay.requestLayout();
                onsOverlay.invalidate();
            }
        });
        onsOverlay.postDelayed(() -> {
            if (onsOverlay != null) onsOverlay.invalidate();
        }, 120);
        Log.i(TAG, "virtual buttons built: " + buttonViews.size() + " buttons, overlay "
                + pw + "x" + ph + ", toggle=" + (toggleButton != null)
                + ", visible=" + controlsVisible);
    }

    private void toggleVirtualControls() {
        controlsVisible = !controlsVisible;
        // 不写入 SharedPreferences：收起只影响本次会话
        applyVirtualControlsVisibility();
    }

    private void applyVirtualControlsVisibility() {
        int vis = controlsVisible ? View.VISIBLE : View.GONE;
        // 自由定位后按钮都是直接挂在 onsOverlay 上的子 View
        for (TextView b : buttonViews.values()) {
            if (b != null) b.setVisibility(vis);
        }
        if (toggleButton != null) {
            toggleButton.setText(controlsVisible ? "✕" : "☰");
            // 收起状态下它是唯一的恢复入口，调亮一点避免被当成没有按钮
            toggleButton.setAlpha(controlsVisible
                    ? OnsButtonRenderer.toggleAlphaOf(btnConfig)
                    : Math.max(0.75f, OnsButtonRenderer.toggleAlphaOf(btnConfig)));
        }
    }

    /** 某个 toggle 按钮当前是否处于开启态。 */
    private boolean isToggleOn(String id) {
        Boolean b = toggleStates.get(id);
        return b != null && b;
    }

/**
     * 给按钮绑定行为。外观由 {@link OnsButtonRenderer} 负责，这里只管按键事件。
     *
     * 三种模式的语义：
     * - hold：按下就发 keyDown，松手才 keyUp（SKIP 快进要持续生效）
     * - toggle：点一下发一次完整按键并翻转本地高亮（AUTO 自动播放）
     * - press：点一下发一次完整按键
     */
    private void bindButtonBehavior(final TextView tv, final OnsButtonConfig.Item item) {
        final String mode = item.mode();
        final int keyCode = item.keyCode();
        final String id = item.id;
        final boolean isToggle = OnsButtonConfig.MODE_TOGGLE.equals(mode);
        final boolean isHold = OnsButtonConfig.MODE_HOLD.equals(mode);
        final float normalAlpha = OnsButtonRenderer.alphaOf(btnConfig);

        tv.setOnTouchListener((v, e) -> {
            int action = e.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setAlpha(Math.min(1f, normalAlpha + 0.3f));
                if (isHold) {
                    sendKeyDown(keyCode);
                    OnsButtonRenderer.applyShape(ONScripter.this, (TextView) v, true);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(normalAlpha);
                if (isHold) {
                    sendKeyUp(keyCode);
                    OnsButtonRenderer.applyShape(ONScripter.this, (TextView) v, false);
                } else if (isToggle) {
                    sendKeyDown(keyCode);
                    sendKeyUp(keyCode);
                    boolean now = !isToggleOn(id);
                    toggleStates.put(id, now);
                    OnsButtonRenderer.applyText((TextView) v, item, now);
                    OnsButtonRenderer.applyShape(ONScripter.this, (TextView) v, now);
                } else {
                    sendKeyDown(keyCode);
                    sendKeyUp(keyCode);
                }
                return true;
            }
            return true;
        });
    }

    private void bindToggleBehavior(final TextView tv) {
        final float normalAlpha = OnsButtonRenderer.toggleAlphaOf(btnConfig);
        tv.setOnTouchListener((v, e) -> {
            int action = e.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setAlpha(Math.min(1f, normalAlpha + 0.3f));
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(normalAlpha);
                if (action == MotionEvent.ACTION_UP) toggleVirtualControls();
                return true;
            }
            return true;
        });
    }

    private void sendKeyDown(int keyCode) {
        try {
            SDLActivity.onNativeKeyDown(keyCode);
        } catch (Throwable t) {
            Log.w(TAG, "keyDown failed: " + keyCode, t);
        }
    }

    private void sendKeyUp(int keyCode) {
        try {
            SDLActivity.onNativeKeyUp(keyCode);
        } catch (Throwable t) {
            Log.w(TAG, "keyUp failed: " + keyCode, t);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void ensureDefaultFont() {
        if (gameRoot == null || gameRoot.isEmpty()) return;
        try {
            File fallbackFont = new File(gameRoot, "default.ttf");
            if (fallbackFont.exists()) return;
            File builtin = new File(getFilesDir(), "DroidSansFallback.ttf");
            if (!builtin.exists()) return;
            File parent = fallbackFont.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            copyFile(builtin, fallbackFont);
            Log.i(TAG, "default.ttf fallback installed: " + fallbackFont);
        } catch (Throwable t) {
            Log.w(TAG, "install default.ttf fallback failed", t);
        }
    }

    /**
     * 视频文件后备扩展名。
     * ONS 脚本里写的扩展名经常与实际文件不一致（常见于汉化 / 重打包版本，
     * 把 op.mpg 转成 op.mp4 却没改脚本）。按此顺序逐个尝试。
     */
    private static final String[] VIDEO_EXT_CANDIDATES = new String[]{
            ".mpg", ".mpeg", ".mp4", ".avi", ".wmv", ".mkv", ".webm", ".m4v", ".mov"
    };

    private File resolveVideoFile(String raw) {
        File file = resolveGameFile(raw);
        if (file == null) return null;
        if (file.exists()) return file;

        // 原名找不到时，先按大小写不敏感再找一次（部分设备的 exFAT / FAT32 分区区分大小写）。
        File ci = findFileIgnoreCase(file);
        if (ci != null && ci.exists()) return ci;

        // 再逐个试其他容器扩展名，命中即用。
        for (String ext : VIDEO_EXT_CANDIDATES) {
            File alt = replaceExtension(file, ext);
            if (alt.exists()) return alt;
            File altCi = findFileIgnoreCase(alt);
            if (altCi != null && altCi.exists()) return altCi;
        }
        // 全都没有，返回原路径让调用方走「文件不存在」分支报日志。
        return file;
    }

    private File resolveGameFile(String raw) {
        if (raw == null) return null;
        String path = raw.replace('\\', '/').trim();
        if (path.startsWith("file://")) path = path.substring("file://".length());
        File file = new File(path);
        if (!file.isAbsolute()) file = new File(gameRoot, path);
        File ci = findFileIgnoreCase(file);
        return ci != null ? ci : file;
    }

    private File findFileIgnoreCase(File file) {
        if (file == null || file.exists()) return file;
        File parent = file.getParentFile();
        if (parent == null) return null;
        File fixedParent = parent.exists() ? parent : findFileIgnoreCase(parent);
        if (fixedParent == null || !fixedParent.isDirectory()) return null;
        File[] list = fixedParent.listFiles();
        if (list == null) return null;
        String wanted = file.getName();
        for (File f : list) {
            if (f.getName().equalsIgnoreCase(wanted)) return f;
        }
        return null;
    }

    private File replaceExtension(File file, String ext) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        File parent = file.getParentFile();
        return new File(parent == null ? new File(".") : parent, base + ext);
    }

    @Override public void onDestroy() {
        if (videoOverlay != null) {
            videoOverlay.dismiss();
            videoOverlay = null;
        }
        missingReadPaths.clear();
        super.onDestroy();
    }

    /**
     * 修复平板设备上画面不居中的问题
     * SDLActivity默认不设置SurfaceView居中，导致在平板(4:3/16:10)上运行16:9游戏时画面底部对齐
     */
    private void fixSurfaceCentering() {
        try {
            if (mLayout != null && mSurface != null) {
                // 获取当前的LayoutParams
                ViewGroup.LayoutParams lp = mSurface.getLayoutParams();
                if (lp instanceof RelativeLayout.LayoutParams) {
                    RelativeLayout.LayoutParams rlp = (RelativeLayout.LayoutParams) lp;
                    // 添加居中规则
                    rlp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                    mSurface.setLayoutParams(rlp);
                    Log.i(TAG, "Fixed surface centering for tablet display");
                } else {
                    // 如果不是RelativeLayout.LayoutParams，重新创建
                    RelativeLayout.LayoutParams newLp = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT,
                            RelativeLayout.LayoutParams.MATCH_PARENT);
                    newLp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                    mSurface.setLayoutParams(newLp);
                    Log.i(TAG, "Recreated layout params with centering for tablet display");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fixSurfaceCentering failed", t);
        }
    }

    private void fullscreen() {
        Window window = getWindow();
        if (ignoreCutout && Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = window.getDecorView().getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        window.getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private String normalizeRootPath(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.startsWith("file://")) v = v.substring("file://".length());
        if (v.startsWith("content://")) {
            try {
                Uri uri = Uri.parse(v);
                String docId;
                try { docId = android.provider.DocumentsContract.getTreeDocumentId(uri); } catch (Throwable ignored) { docId = null; }
                if (docId == null || docId.isEmpty()) docId = android.provider.DocumentsContract.getDocumentId(uri);
                String path = docIdToPath(docId);
                if (path != null) return path;
            } catch (Throwable ignored) { }
        }
        return v;
    }

    private String docIdToPath(String docId) {
        if (docId == null) return null;
        int colon = docId.indexOf(':');
        String volume = colon >= 0 ? docId.substring(0, colon) : docId;
        String rel = colon >= 0 ? docId.substring(colon + 1) : "";
        if ("primary".equalsIgnoreCase(volume)) return "/storage/emulated/0" + (rel.isEmpty() ? "" : "/" + rel);
        if (volume != null && !volume.isEmpty()) return "/storage/" + volume + (rel.isEmpty() ? "" : "/" + rel);
        return null;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    private String decodePath(byte[] pathbyte) {
        return new String(pathbyte, StandardCharsets.UTF_8).replace('\\', '/');
    }

    private void copyFile(File from, File to) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from);
             java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
