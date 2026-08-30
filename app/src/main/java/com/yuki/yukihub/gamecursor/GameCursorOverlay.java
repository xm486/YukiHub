package com.yuki.yukihub.gamecursor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 游戏内悬浮光标层。
 *
 * 两种挂载方式，按引擎特性自动选择：
 * - DecorView（KRKR 等普通 Activity）：直接 addView，不需要任何权限。
 * - 独立窗口（Artemis 等 NativeActivity）：走 WindowManager 添加系统层窗口。
 *   NativeActivity 用 takeSurface() 把窗口 Surface 交给 native 层，
 *   日志可见 BufferQueueProducer api=1（EGL 直连），ViewRootImpl 的 HWUI 管线被接管，
 *   挂在 DecorView 上的 Java View 永远不会被绘制 —— 因此必须用独立窗口。
 *
 * 两种模式，由常驻浮标按钮切换：
 * - 鼠标模式：全屏滑动 = 光标相对移动（像笔电触控板），抬手 = 在光标尖端注入点击。
 * - 直接触摸模式：不拦事件，触摸原样透给游戏，光标隐藏。
 *
 * 视图层级（容器内自下而上）：捕获层 → 光标 → 模式浮标。
 * 浮标在两种模式下都可见可点，杜绝"切进去出不来"。
 */
public final class GameCursorOverlay {
    private static final String TAG = "YukiGameCursor";
    /** scale=1.0 时光标的基准高度。默认 scale=0.6 → 约 36dp，即"默认缩小"。 */
    private static final float BASE_CURSOR_DP = 60f;
    /** 模式浮标直径（dp）。 */
    private static final float TOGGLE_DP = 44f;
    /** 判定"轻点"的最大位移（dp）：超过就只当移动光标，不注入点击。 */
    private static final float TAP_SLOP_DP = 8f;
    /** 悬停上报间隔（毫秒），约 60Hz。 */
    private static final long HOVER_INTERVAL_MS = 16;

    private final Activity host;
    private GameCursorConfig config;
    private final GameCursorInjector injector;
    private final FrameLayout container;
    private final CaptureView capture;
    private final CursorView cursor;
    private final ToggleView toggle;
    private boolean attached;
    private boolean mouseMode;
    /** 是否已完成首次定位（等到窗口有真实尺寸后才置位）。 */
    private boolean placed;
    private int lastW, lastH;
    /** true = 走 WindowManager 独立窗口（NativeActivity）；false = 挂 DecorView。 */
    private boolean useWindowManager;
    private android.view.WindowManager windowManager;
    private boolean overlayPermissionMissing;
    /** 注入手势期间为 true：此时窗口折叠让路，不能重入。 */
    private boolean injecting;
    /** 宿主 Activity 不在前台时为 true：窗口必须挂起，否则会锁死系统界面。 */
    private boolean suspended;
    /** 独立窗口模式下浮标的宿主窗口与参数（浮标必须独立成窗口才能始终可点）。 */
    private FrameLayout toggleHost;
    private android.view.WindowManager.LayoutParams toggleParams;

    public GameCursorOverlay(Activity host, GameCursorConfig config, GameCursorInjector injector) {
        this.host = host;
        this.config = config;
        this.injector = injector;
        this.mouseMode = config.mouseMode;

        container = new FrameLayout(host);
        // 容器本身绝不拦事件：直接触摸模式下，触摸必须原样落到下面的游戏 View。
        // FrameLayout 默认不 clickable，但显式声明避免被主题/后续改动带上。
        container.setClickable(false);
        container.setFocusable(false);
        container.setFocusableInTouchMode(false);
        capture = new CaptureView(host);
        cursor = new CursorView(host);
        toggle = new ToggleView(host);

        container.addView(capture, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        container.addView(cursor, new FrameLayout.LayoutParams(1, 1));
        int td = dp(TOGGLE_DP);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(td, td);
        container.addView(toggle, tlp);
    }

    public void show() {
        if (attached) return;
        try {
            // NativeActivity 的 Surface 被 native 层接管，DecorView 上的 View 不会被绘制，
            // 必须改用 WindowManager 独立窗口（依赖悬浮窗权限）。
            useWindowManager = isNativeSurfaceActivity();
            if (useWindowManager) {
                showAsWindow();
            } else {
                showInDecor();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "show failed", t);
        }
    }

    /** NativeActivity 系（Artemis）：Surface 交给 native，Java View 树不参与绘制。 */
    private boolean isNativeSurfaceActivity() {
        return host instanceof android.app.NativeActivity;
    }

    private void showInDecor() {
        ViewGroup decor = (ViewGroup) host.getWindow().getDecorView();
        if (container.getParent() == decor) {
            attached = true;
            return;
        }
        decor.addView(container, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        attached = true;
        try {
            container.setElevation(1000f);
            container.bringToFront();
        } catch (Throwable ignored) { }
        finishAttach("decor");
    }

    private void showAsWindow() {
        if (!android.provider.Settings.canDrawOverlays(host)) {
            android.util.Log.w(TAG, "overlay permission missing, cursor unavailable");
            overlayPermissionMissing = true;
            return;
        }
        android.view.WindowManager wm =
                (android.view.WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
        lp.type = android.os.Build.VERSION.SDK_INT >= 26
                ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : android.view.WindowManager.LayoutParams.TYPE_PHONE;
        lp.format = android.graphics.PixelFormat.TRANSLUCENT;
        lp.gravity = android.view.Gravity.START | android.view.Gravity.TOP;
        lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = android.view.WindowManager.LayoutParams.MATCH_PARENT;
        // NOT_FOCUSABLE：不抢输入焦点，游戏按键不受影响
        // LAYOUT_NO_LIMITS + LAYOUT_IN_SCREEN：覆盖到刘海/导航栏区域，与游戏全屏对齐
        lp.flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        windowManager = wm;
        // 主窗口：捕获层 + 光标
        container.removeView(toggle);
        wm.addView(container, lp);

        // 浮标单独一个小窗口：主窗口在直接触摸模式会被设成 NOT_TOUCHABLE，
        // 浮标必须留在自己的窗口里才始终可点，否则切过去就切不回来。
        toggleHost = new FrameLayout(host);
        toggleHost.setClickable(false);
        int side = dp(TOGGLE_DP);
        toggleHost.addView(toggle, new FrameLayout.LayoutParams(side, side));
        android.view.WindowManager.LayoutParams tlp = new android.view.WindowManager.LayoutParams();
        tlp.type = lp.type;
        tlp.format = android.graphics.PixelFormat.TRANSLUCENT;
        tlp.gravity = android.view.Gravity.START | android.view.Gravity.TOP;
        tlp.width = side;
        tlp.height = side;
        tlp.flags = BASE_WINDOW_FLAGS;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            tlp.layoutInDisplayCutoutMode = lp.layoutInDisplayCutoutMode;
        }
        // 默认右下角。用真实屏幕尺寸，否则在刘海屏上会偏左一块。
        android.graphics.Point screen = realScreenSize();
        tlp.x = Math.max(0, screen.x - side - dp(12));
        tlp.y = Math.max(0, screen.y - side - dp(12));
        toggleParams = tlp;
        wm.addView(toggleHost, tlp);

        attached = true;
        finishAttach("window");
    }

    /** 挂载完成后的公共收尾：等真实尺寸就绪再定位。 */
    private void finishAttach(String how) {
        applyConfig();
        // NativeActivity（Artemis）的窗口在 handleResumeActivity 才拿到尺寸，
        // 此刻可能还是 0×0，post 也排在未 attach 的 View 上不会跑。
        // 因此监听真实布局：尺寸首次有效时定位，尺寸变化（旋转/分屏）时重新夹取。
        container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                int w = r - l, h = b - t;
                if (w <= 0 || h <= 0) return;
                if (!placed) {
                    placed = true;
                    cursor.snapTipTo(w * 0.5f, h * 0.42f);
                    toggle.placeDefault(w, h);
                    applyMode();
                    android.util.Log.i(TAG, "overlay placed " + w + "x" + h);
                } else if (w != lastW || h != lastH) {
                    cursor.snapTipTo(cursor.tipX(), cursor.tipY());
                    toggle.clampIntoParent(w, h);
                }
                lastW = w;
                lastH = h;
            }
        });
        if (container.getWidth() > 0 && container.getHeight() > 0) {
            placed = true;
            lastW = container.getWidth();
            lastH = container.getHeight();
            cursor.snapTipTo(lastW * 0.5f, lastH * 0.42f);
            toggle.placeDefault(lastW, lastH);
            applyMode();
        }
        android.util.Log.i(TAG, "overlay shown via " + how + " mouseMode=" + mouseMode
                + " size=" + container.getWidth() + "x" + container.getHeight());
    }

    /** 独立窗口方案缺少悬浮窗权限时为 true，供上层提示用户。 */
    public boolean isOverlayPermissionMissing() {
        return overlayPermissionMissing;
    }

    public void dismiss() {
        attached = false;
        // 销毁前复位：可能正卡在「按下未抬起」，不放开会把引擎的按键状态留在按下态
        try { injector.reset(); } catch (Throwable ignored) { }
        try {
            if (useWindowManager && windowManager != null) {
                try { windowManager.removeViewImmediate(container); } catch (Throwable ignored) { }
                if (toggleHost != null) {
                    try { windowManager.removeViewImmediate(toggleHost); } catch (Throwable ignored) { }
                    toggleHost = null;
                }
                windowManager = null;
            } else if (container.getParent() instanceof ViewGroup) {
                ((ViewGroup) container.getParent()).removeView(container);
            }
        } catch (Throwable ignored) { }
    }

    public boolean isAttached() {
        return attached;
    }

    /** 配置变化后重新应用外观（图标/缩放/透明度/灵敏度）。 */
    public void applyConfig() {
        config = GameCursorConfig.load(host);
        cursor.applyAppearance();
        applyMode();
    }

    private void setMouseMode(boolean enable) {
        if (mouseMode == enable) return;
        mouseMode = enable;
        GameCursorConfig.saveMouseMode(host, enable);
        applyMode();
        android.util.Log.i(TAG, "mouseMode=" + enable);
    }

    /** 按当前模式切换捕获层与光标的可见性/拦截行为。 */
    private void applyMode() {
        // 鼠标模式：捕获层可点（吃触摸）+ 光标可见
        // 直接触摸：捕获层不可点（事件透传给下面的游戏 View）+ 光标隐藏
        capture.setClickable(mouseMode);
        capture.setFocusable(false);
        cursor.setVisibility(mouseMode ? View.VISIBLE : View.GONE);
        toggle.invalidate();
        updateWindowTouchability();
        // 进入鼠标模式时同步一次位置：否则引擎的鼠标位置还是上次退出时的旧值，
        // 光标显示在一处、引擎认为在另一处，悬停高亮就会错位。
        if (mouseMode) {
            container.post(() -> {
                if (attached && mouseMode) {
                    injector.hover(cursor.tipX(), cursor.tipY());
                }
            });
        }
    }

    /**
     * 注入点击。
     *
     * 独立窗口模式（Artemis）的冲突：无障碍手势按**屏幕坐标**派发，
     * 而我们的窗口在鼠标模式下 touchable 且覆盖全屏，手势会先命中自己被吃掉。
     *
     * 走过三个错方案，都记在这里避免重犯：
     * 1. FLAG_NOT_TOUCHABLE 临时放行后立刻恢复 —— 恢复时 WindowManager 重做
     *    命中判定，手指刚抬起的位置被补发给游戏，表现为「按的地方也被触发」。
     * 2. 窗口折叠成 1x1 —— 改尺寸会让 Artemis 的 native surface 反复
     *    disconnect/connect（`handleResized abandoned!`），破坏引擎输入管线。
     * 3. 窗口移出屏幕（只改 x）—— 无效。因为窗口带 FLAG_LAYOUT_NO_LIMITS，
     *    不受屏幕边界约束，移出后触摸区域仍然有效，手势照样被截走。
     *    日志铁证：每次点击产生两次 `tap at`，第二次坐标 +1，
     *    正好是手势路径 lineTo(x+1,y+1) 的终点被自己的捕获层收到。
     *
     * 现在的做法：整个注入窗口期都保持 NOT_TOUCHABLE，
     * 并且**等手势完成回调之后**再恢复。方案 1 的补发问题源于恢复太早
     * （手指还在屏上/刚抬起），等手势跑完再恢复就不会命中残留的触摸序列。
     * DecorView 模式（KRKR）走同进程 MotionEvent 派发，无此问题，直接注入。
     */
    private void injectTapThroughWindow(float px, float py) {
        if (!useWindowManager || windowManager == null) {
            injector.tap(px, py);
            return;
        }
        // native 通道可用时完全不用动窗口：它直接调引擎内部函数，
        // 不经过系统输入派发，悬浮窗挡不住它。
        // 下面那套窗口 hack 只为无障碍手势服务。
        if (injector.isNativeChannelReady()) {
            injector.tap(px, py);
            return;
        }
        if (injecting) return; // 上一次注入还没收尾，忽略
        try {
            injecting = true;
            setInjectionPassthrough(true);
            // updateViewLayout 是异步的：必须等标记真正生效再发手势。
            container.post(() -> {
                injector.tap(px, py);
                // 等足够长：手势时长(60) + 派发 + 触摸序列走完。
                // 恢复太早会让残留的触摸被补发（方案 1 踩过）。
                container.postDelayed(() -> {
                    injecting = false;
                    if (attached && !suspended) setInjectionPassthrough(false);
                }, 350);
            });
        } catch (Throwable t) {
            android.util.Log.w(TAG, "injectTapThroughWindow failed", t);
            injecting = false;
            setInjectionPassthrough(false);
        }
    }

    /**
     * 注入期间让窗口完全不接触摸。
     *
     * 只用 FLAG_NOT_TOUCHABLE，不动位置也不动尺寸：
     * 动尺寸会重建 Artemis 的 surface，动位置因 LAYOUT_NO_LIMITS 而无效。
     */
    private void setInjectionPassthrough(boolean passthrough) {
        if (!useWindowManager || windowManager == null || !attached) return;
        applyTouchableFlag(container, !passthrough);
        if (toggleHost != null) applyTouchableFlag(toggleHost, !passthrough);
    }

    /**
     * 挂起/恢复整个光标层。
     *
     * 必须有这个：独立窗口是 TYPE_APPLICATION_OVERLAY，
     * 它**不跟随宿主 Activity 的可见性**。离开游戏（Home、切后台、跳系统设置）后
     * 窗口仍然盖在系统界面之上，鼠标模式下还是 touchable，
     * 会把整屏触摸全部吃掉 —— 表现为「退出游戏后手机完全没法操作」。
     */
    public void setSuspended(boolean suspend) {
        if (!attached) return;
        if (suspended == suspend) return;
        suspended = suspend;
        try {
            // 切走时复位注入状态：否则若正好卡在「按下未抬起」，
            // 抬起回调可能不执行，之后点击会被永久丢弃。
            if (suspend) injector.reset();
            int vis = suspend ? View.GONE : View.VISIBLE;
            container.setVisibility(vis);
            if (toggleHost != null) toggleHost.setVisibility(vis);
            if (useWindowManager && windowManager != null) {
                // GONE 不足以让窗口退出触摸命中：窗口本体仍在，
                // 而 LAYOUT_NO_LIMITS 让它连屏幕边界都不受约束。
                // 必须用 NOT_TOUCHABLE 才能真正让系统界面不受影响。
                setInjectionPassthrough(suspend);
            }
            android.util.Log.i(TAG, suspend ? "suspended" : "resumed");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "setSuspended failed", t);
        }
    }

    private void applyTouchableFlag(View target, boolean touchable) {
        try {
            android.view.WindowManager.LayoutParams lp =
                    (android.view.WindowManager.LayoutParams) target.getLayoutParams();
            if (lp == null) return;
            int flags = touchable
                    ? BASE_WINDOW_FLAGS
                    : BASE_WINDOW_FLAGS | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (lp.flags == flags) return;
            lp.flags = flags;
            windowManager.updateViewLayout(target, lp);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "applyTouchableFlag failed", t);
        }
    }

    /**
     * 独立窗口模式下的触摸可达性。
     *
     * 独立窗口覆盖在游戏之上，直接触摸模式必须整体不接触摸，
     * 否则整屏事件都被它挡住，游戏彻底点不动。
     *
     * 做法：主窗口（捕获层 + 光标）按模式切 FLAG_NOT_TOUCHABLE；
     * 浮标放在自己的小窗口里，始终可点 —— 这样不用在两套坐标系间换算，
     * 也保证「切进直接触摸后还能切回来」。
     */
    private void updateWindowTouchability() {
        if (!useWindowManager || windowManager == null || !attached) return;
        // 注入中或挂起中窗口已折叠，此刻改 flags 会把折叠状态覆盖掉
        if (injecting || suspended) return;
        // 主窗口跟随模式；浮标窗口始终可点，保证永远能切回来
        applyTouchableFlag(container, mouseMode);
        if (toggleHost != null) applyTouchableFlag(toggleHost, true);
    }

    private static final int BASE_WINDOW_FLAGS =
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    /**
     * 真实屏幕尺寸（含刘海/导航栏区域）。
     *
     * 不能用 getResources().getDisplayMetrics()：它返回的是应用可用区域，
     * 在刘海屏上比实际窗口窄（本机 2414 实际 vs 可用区更小），
     * 会导致光标和浮标到不了屏幕最右边一块。
     * 窗口用了 LAYOUT_NO_LIMITS 铺满物理屏，这里必须取同一个基准。
     */
    private android.graphics.Point realScreenSize() {
        android.graphics.Point p = new android.graphics.Point();
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowMetrics wmx = wm.getCurrentWindowMetrics();
                    android.graphics.Rect b = wmx.getBounds();
                    p.set(b.width(), b.height());
                } else {
                    wm.getDefaultDisplay().getRealSize(p);
                }
            }
        } catch (Throwable ignored) { }
        if (p.x <= 0 || p.y <= 0) {
            android.util.DisplayMetrics dm = host.getResources().getDisplayMetrics();
            p.set(dm.widthPixels, dm.heightPixels);
        }
        return p;
    }

    private int dp(float v) {
        return Math.max(1, Math.round(v * host.getResources().getDisplayMetrics().density));
    }

    /**
     * 全屏触摸捕获层。
     * 鼠标模式下 clickable=true，onTouchEvent 返回 true 吃掉事件；
     * 直接触摸模式下 clickable=false 且返回 false，事件继续向下传给游戏 View。
     */
    private final class CaptureView extends View {
        private float lastX, lastY;
        private float travel;
        private boolean tracking;
        private long lastHoverAt;

        CaptureView(Context c) {
            super(c);
            setBackgroundColor(0x00000000);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!mouseMode) return false; // 透传给游戏
            // 注入期间拒收一切事件：否则我们自己派发的手势会被这一层收到，
            // 游戏永远拿不到（日志表现为一次点击产生两次 tap at，第二次坐标 +1）。
            // 这是窗口标记之外的第二道保险，两者都必须有。
            if (injecting) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    tracking = true;
                    travel = 0f;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    cursor.setTapFeedback(true);
                    // 按下时立刻上报一次位置。
                    //
                    // 必需：如果手指按下后几乎不动就抬手（正常点击的样子），
                    // MOVE 分支可能一次都没跑到、或位移为 0 被去重跳过，
                    // 引擎的鼠标位置就还停在上一次的地方 ——
                    // 表现为悬停高亮位置与光标不一致、"一会能一会不能"。
                    lastHoverAt = android.os.SystemClock.uptimeMillis();
                    injector.hover(cursor.tipX(), cursor.tipY());
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!tracking) return true;
                    float rx = event.getRawX(), ry = event.getRawY();
                    float dx = rx - lastX, dy = ry - lastY;
                    lastX = rx;
                    lastY = ry;
                    travel += Math.abs(dx) + Math.abs(dy);
                    float k = clamp(config.sensitivity, 0.5f, 3f);
                    cursor.moveBy(dx * k, dy * k);
                    // 上报悬停位置，触发引擎的按钮高亮等 hover 表现。
                    // 按 16ms 节流（约 60Hz），避免高频 JNI 调用拖慢引擎主循环。
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastHoverAt >= HOVER_INTERVAL_MS) {
                        lastHoverAt = now;
                        injector.hover(cursor.tipX(), cursor.tipY());
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean wasUp = event.getActionMasked() == MotionEvent.ACTION_UP;
                    tracking = false;
                    cursor.setTapFeedback(false);
                    // 抬手后补一次 hover：让光标停留处的按钮保持高亮，
                    // 否则最后一次 MOVE 可能被节流吃掉，高亮状态与光标位置不一致。
                    injector.hover(cursor.tipX(), cursor.tipY());
                    // 只有"几乎没移动"才算点击，避免每次移动光标都误触发
                    if (wasUp && travel <= dp(TAP_SLOP_DP)) {
                        float px = cursor.tipX(), py = cursor.tipY();
                        android.util.Log.d(TAG, "tap at (" + (int) px + "," + (int) py + ")");
                        injectTapThroughWindow(px, py);
                    }
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    private final class CursorView extends View {
        /** 外观（光标包 / PNG / 内置箭头）的唯一来源，与设置界面预览共用同一个类。 */
        private final CursorAppearance appearance = new CursorAppearance();
        private boolean pressedState;
        /** 尖端当前坐标（容器坐标系）。位置状态以尖端为准，不用视图左上角。 */
        private float tipCx, tipCy;
        /** 动画帧推进任务；非动画时为 null。 */
        private Runnable frameTicker;
        /**
         * 已加载的外观来源快照。
         *
         * 用来避免重复解码。不能拿 config 对象前后比较 ——
         * applyConfig 每次都 load 出新对象，但内容往往一样。
         */
        private boolean appearanceLoaded;
        private String loadedWinName;
        private String loadedIconUri;

        CursorView(Context c) {
            super(c);
            // 光标只是"显示"，不接触摸：鼠标模式下所有触摸都由捕获层处理。
            // 否则手指恰好划过光标时会被它截走，出现移动断连。
            setClickable(false);
            setFocusable(false);
            applyAppearance();
        }

        private int width() {
            return getWidth() > 0 ? getWidth() : getLayoutParams().width;
        }

        private int height() {
            return getHeight() > 0 ? getHeight() : getLayoutParams().height;
        }

        void applyAppearance() {
            // 只在外观来源真变了时才重解码。
            // applyAppearance 会被构造、finishAttach、配置变化多次调用，
            // 每次重解 60 帧 PNG（分配又回收 60 个 Bitmap）是纯浪费 ——
            // 实测进游戏 87ms 内连解 3 次。
            String wantWin = config != null ? config.winCursorName : null;
            String wantIcon = config != null ? config.iconUri : null;
            if (!appearanceLoaded
                    || !eqStr(loadedWinName, wantWin)
                    || !eqStr(loadedIconUri, wantIcon)) {
                appearance.load(host, config);
                loadedWinName = wantWin;
                loadedIconUri = wantIcon;
                appearanceLoaded = true;
                // 只在真换了图时重启动画。否则调缩放会把动画打回第一帧。
                restartAnimation();
            }
            float base = BASE_CURSOR_DP * host.getResources().getDisplayMetrics().density
                    * clamp(config.scale, GameCursorConfig.MIN_SCALE, GameCursorConfig.MAX_SCALE);
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(1, 1);
                setLayoutParams(lp);
            }
            // 用 appearance 报告的宽高比统一算尺寸：
            // 光标包是正方形（128×128），PNG 任意，内置箭头是 ASPECT。
            float aspect = appearance.aspect();
            if (aspect >= 1f) {
                lp.width = Math.max(1, Math.round(base));
                lp.height = Math.max(1, Math.round(base / aspect));
            } else {
                lp.height = Math.max(1, Math.round(base));
                lp.width = Math.max(1, Math.round(base * aspect));
            }
            setLayoutParams(lp);
            setAlpha(clamp(config.alpha, 0.2f, 1f));
            requestLayout();
            invalidate();
        }

        private boolean eqStr(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        /** 启动/重启动画帧推进。非动画光标什么也不做。 */
        private void restartAnimation() {
            stopAnimation();
            if (!appearance.isAnimated()) return;
            appearance.resetAnimation();
            frameTicker = new Runnable() {
                @Override
                public void run() {
                    if (frameTicker != this) return;   // 已被替换/停止
                    appearance.advance();
                    invalidate();
                    int delay = Math.max(16, appearance.currentDurationMs());
                    postDelayed(this, delay);
                }
            };
            postDelayed(frameTicker, Math.max(16, appearance.currentDurationMs()));
        }

        private void stopAnimation() {
            if (frameTicker != null) {
                removeCallbacks(frameTicker);
                frameTicker = null;
            }
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            // 不可见时停掉动画，别白耗电（直接触摸模式下光标是 GONE 的）
            if (visibility == VISIBLE) {
                if (frameTicker == null) restartAnimation();
            } else {
                stopAnimation();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stopAnimation();
            appearance.release();
            super.onDetachedFromWindow();
        }

        /** 按下态（画高亮/缩放反馈用）。避免与 View.setPressed 撞名。 */
        void setTapFeedback(boolean p) {
            if (pressedState == p) return;
            pressedState = p;
            invalidate();
        }

        /**
         * 把光标尖端移到 (tx, ty)（容器坐标），并保证尖端始终落在屏幕内。
         *
         * 关键：夹取的是**尖端**而不是整个图标包围盒。
         * 之前按整图夹取会让尖端永远到不了屏幕右/下边缘
         * （日志里 tap 最大只到 y=1021，屏幕高 1080，底部按钮点不到）。
         */
        void snapTipTo(float tx, float ty) {
            int pw, ph;
            if (useWindowManager) {
                // 独立窗口用了 LAYOUT_NO_LIMITS，可达范围是整块物理屏；
                // container.getWidth() 会受 inset 影响偏小，导致右侧一条点不到。
                android.graphics.Point screen = realScreenSize();
                pw = screen.x;
                ph = screen.y;
            } else {
                pw = container.getWidth();
                ph = container.getHeight();
            }
            int vw = width(), vh = height();
            if (pw <= 0 || ph <= 0 || vw <= 0 || vh <= 0) {
                // 尚未测量完成，等下一帧再定位，避免算出屏外坐标把光标"弄丢"
                post(() -> snapTipTo(tx, ty));
                return;
            }
            // 尖端可达 [0, pw-1] × [0, ph-1]：整屏每个像素都点得到
            float cx = clamp(tx, 0f, Math.max(0f, pw - 1f));
            float cy = clamp(ty, 0f, Math.max(0f, ph - 1f));
            tipCx = cx;
            tipCy = cy;
            // 由尖端反推视图左上角。热点位置来自 appearance：
            // Windows 光标包用文件里的真实热点（不同角色差异极大，
            // 比如文本选择在 (0.086, 0.523)、对角2 在 (0.766, 0.227)），
            // PNG 和内置箭头用各自的约定值。硬编码常数在换图后必然错位。
            setX(cx - vw * appearance.hotspotX());
            setY(cy - vh * appearance.hotspotY());
        }

        /** 相对移动（鼠标模式核心）：按尖端位置累加。 */
        void moveBy(float dx, float dy) {
            snapTipTo(tipCx + dx, tipCy + dy);
        }

        /** 光标尖端坐标：注入点击/悬停用。 */
        float tipX() {
            return tipCx;
        }

        float tipY() {
            return tipCy;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            appearance.draw(canvas, getWidth(), getHeight(), pressedState);
        }
    }

    /**
     * 模式切换浮标：常驻右下角，可拖动换位置。
     * 单击切模式，拖动移动自身——两种模式下都可交互，保证永远有退路。
     */
    private final class ToggleView extends View {
        private Paint bg, fg;
        private float grabRawDx, grabRawDy;
        private float travel;

        ToggleView(Context c) {
            super(c);
            // 必须 clickable：浮标在捕获层之上（后 addView），
            // clickable 才能让事件分发在它身上停下，不被下面的捕获层吃掉。
            setClickable(true);
        }

        void placeDefault(int pw, int ph) {
            if (useWindowManager) return; // 窗口模式下位置由 toggleParams 控制
            int size = getWidth() > 0 ? getWidth() : dp(TOGGLE_DP);
            setX(pw - size - dp(12));
            setY(ph - size - dp(12));
        }

        /** 窗口尺寸变化后把自己夹回可见范围。 */
        void clampIntoParent(int pw, int ph) {
            if (useWindowManager) return;
            int size = getWidth() > 0 ? getWidth() : dp(TOGGLE_DP);
            setX(clamp(getX(), 0, Math.max(0, pw - size)));
            setY(clamp(getY(), 0, Math.max(0, ph - size)));
        }

        /** 窗口模式：拖动时移动的是浮标自己的窗口。 */
        private void moveWindowBy(float rawX, float rawY) {
            if (windowManager == null || toggleHost == null || toggleParams == null) return;
            int size = dp(TOGGLE_DP);
            android.graphics.Point screen = realScreenSize();
            toggleParams.x = Math.round(clamp(rawX - size * 0.5f, 0, Math.max(0, screen.x - size)));
            toggleParams.y = Math.round(clamp(rawY - size * 0.5f, 0, Math.max(0, screen.y - size)));
            try {
                windowManager.updateViewLayout(toggleHost, toggleParams);
            } catch (Throwable ignored) { }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (bg == null) {
                bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setStyle(Paint.Style.FILL);
                fg = new Paint(Paint.ANTI_ALIAS_FLAG);
                fg.setStyle(Paint.Style.STROKE);
                fg.setStrokeCap(Paint.Cap.ROUND);
                fg.setStrokeJoin(Paint.Join.ROUND);
            }
            // 鼠标模式用高亮蓝，直接触摸用中性灰，一眼分清当前状态
            bg.setColor(mouseMode ? 0xCC2B6CD6 : 0x99333333);
            canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, bg);

            fg.setColor(0xFFFFFFFF);
            fg.setStrokeWidth(Math.max(2f, w * 0.06f));
            int sc = canvas.save();
            // 图标：鼠标模式画小箭头，直接触摸画手指点触
            canvas.translate(w * 0.3f, h * 0.26f);
            float iw = w * 0.4f, ih = h * 0.48f;
            if (mouseMode) {
                canvas.drawPath(GameCursorIconRenderer.buildArrowPath(iw, ih), fg);
            } else {
                // 手指轻点：一个圆 + 两道扩散弧
                canvas.drawCircle(iw * 0.5f, ih * 0.62f, iw * 0.28f, fg);
                canvas.drawLine(iw * 0.5f, ih * 0.02f, iw * 0.5f, ih * 0.22f, fg);
            }
            canvas.restoreToCount(sc);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    travel = 0f;
                    grabRawDx = event.getRawX() - getX();
                    grabRawDy = event.getRawY() - getY();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (useWindowManager) {
                        travel += 4f; // 窗口模式下按 raw 移动，累积一个近似位移即可判定拖拽
                        moveWindowBy(event.getRawX(), event.getRawY());
                        return true;
                    }
                    float nx = event.getRawX() - grabRawDx;
                    float ny = event.getRawY() - grabRawDy;
                    travel += Math.abs(nx - getX()) + Math.abs(ny - getY());
                    int pw = container.getWidth(), ph = container.getHeight();
                    if (pw > 0 && ph > 0) {
                        setX(clamp(nx, 0, Math.max(0, pw - getWidth())));
                        setY(clamp(ny, 0, Math.max(0, ph - getHeight())));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (travel <= dp(TAP_SLOP_DP)) setMouseMode(!mouseMode);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}