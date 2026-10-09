package com.yuki.yukihub.gamecursor;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.yuki.yukihub.fvp.NativeRfvp;
/**
 * 点击注入器：把悬浮光标的"点击"投递给游戏引擎。
 * <p>
 * 注意：InputDevice 常量保留 import，供后续 pointerProperties 方案使用；
 * 当前合成事件走单指 12 参重载（deviceId=0）。
 *
 * 两种通道：
 * - KRKR：合成 MotionEvent 派发给 GLSurfaceView，复用 h.java 的既有触摸管线
 *   （0.9.x → KR2Activity.nativeTouches*；1.3.4 → 标准 Cocos 管线），
 *   线程模型与真实触摸一致（GLSurfaceView 内部排队到 GL 线程）。
 * - Artemis：无障碍手势（dispatchGesture），与真实触摸在系统层等价，
 *   覆盖 native 输入队列 / NativeActivity 的所有路径。
 */
public final class GameCursorInjector {
    private static final String TAG = "YukiGameCursor";
    /** 派发给 krkr GLSurfaceView 的合成触摸 id（真实触摸一般用 0..9，取高位避免冲突）。 */
    private static final int INJECT_TOUCH_ID = 11;
    /** 两次注入的最小间隔，防止超频导致引擎输入队列积压。 */
    private static final long MIN_INJECT_INTERVAL_MS = 120;
    /** 合成点击的按压时长（毫秒）。KRKR 的 MotionEvent 用。 */
    private static final long TAP_DURATION_MS = 50;
    /**
     * Artemis native 注入的按下保持时长（毫秒）。
     *
     * 比 KRKR 的 50ms 长得多，因为机制不同：native 通道是直接写引擎的
     * 按键状态数组，引擎**按帧轮询** IsPush/IsDown 才能看到。
     * 50ms 在 60fps 下只有 3 帧，一旦掉帧就可能整个按下期都没被采到 ——
     * 表现为「有时候点了没反应」。
     * 120ms 即使掉到 30fps 也能覆盖 3 帧以上，留足余量。
     */
    private static final long ARTEMIS_PRESS_HOLD_MS = 120;
    /**
     * 抬起边沿的保持时长（毫秒）。
     *
     * 引擎要在自己的帧循环里读到 IsUpEdge 才会触发按钮确认，
     * 所以写 4 之后不能立刻清 0。80ms 在 30fps 下也能跨 2 帧以上。
     */
    private static final long ARTEMIS_UP_EDGE_HOLD_MS = 80;
    /**
     * Artemis hover 失败后的重试间隔。
     *
     * 200ms 是权衡：足够稀疏（不会每帧白跑 JNI），也足够密
     * （引擎就绪后最多 200ms 悬停就恢复，用户感觉不到）。
     */
    private static final long HOVER_RETRY_INTERVAL_MS = 200;
    /**
     * FVP 合成点击的按压保持时长（毫秒）。
     *
     * <p>rfvp 脚本每帧读 {@code InputGetDown} 边沿，同一帧内 down+up 会被合并成
     * "无事发生"。50ms 在 60fps 下约 3 帧，与 KRKR 通道手感一致。
     */
    private static final long FVP_PRESS_HOLD_MS = 50L;
    private static final int TARGET_KRKR = 0;
    private static final int TARGET_ARTEMIS = 1;
    /** FVP（rfvp）：直接走宿主 C ABI（rfvp_android_touch/key），无队列、无系统派发。 */
    private static final int TARGET_FVP = 2;

    private int target = TARGET_KRKR;
    private Object krkrSurfaceView; // 实际类型 android.view.View（Cocos2dxGLSurfaceView）
    private long lastInjectAt;
    /**
     * FVP 目标句柄提供者（懒取，避免 FvpActivity 里 handle 晚于光标创建）。
     * 返回 0 表示引擎尚未就绪，此时点击/悬停直接丢弃（下次即可）。
     */
    public interface FvpHandleProvider {
        long fvpHandle();
        /**
         * 把光标所在的 DecorView 坐标换算成引擎需要的内容 View 坐标。
         * 默认（FVP 未放大画面时）两者一致；开了画面放大则需减去 SurfaceView 的窗口偏移。
         * 返回 {@code out}，长度为 2。
         */
        default float[] toContentCoords(float rawX, float rawY, float[] out) {
            out[0] = rawX;
            out[1] = rawY;
            return out;
        }
    }

    private FvpHandleProvider fvpHandleProvider;

    public void setKrkrTarget(Object glSurfaceView) {
        this.target = TARGET_KRKR;
        this.krkrSurfaceView = glSurfaceView;
    }

    public void setArtemisTarget() {
        this.target = TARGET_ARTEMIS;
        this.krkrSurfaceView = null;
    }

    /**
     * FVP 目标：点击/悬停直接投给 rfvp 的宿主 C ABI。
     *
     * <p>坐标语义：FVP 的 {@code rfvp_android_touch} 接收的是 **SurfaceView 物理像素坐标**，
     * 引擎内部再做 keep-aspect 换算。光标所在容器与 SurfaceView 同源（都铺在 rootLayout 上），
     * 因此这里直接用容器坐标即可，无需再减偏移。
     */
    public void setFvpTarget(FvpHandleProvider provider) {
        this.target = TARGET_FVP;
        this.krkrSurfaceView = null;
        this.fvpHandleProvider = provider;
    }

    private long fvpHandle() {
        FvpHandleProvider p = fvpHandleProvider;
        if (p == null) return 0L;
        try {
            return p.fvpHandle();
        } catch (Throwable t) {
            return 0L;
        }
    }


    /**
     * 移动光标时上报悬停位置，让引擎产生鼠标悬停效果
     * （按钮高亮、选项变色等依赖 onMouseMove 的表现）。
     *
     * KRKR：走 KR2Activity.nativeHoverMoved —— 这是引擎自身的 hover 入口，
     * libgame.so 与 libgame134.so 都导出了该 JNI，两个引擎版本通用。
     * Artemis：走 native 桥直接调 CInputBase::ReportMousePosition。
     * 之前判断「Artemis 无悬停能力」是基于 JNI 层未导出，
     * 但引擎内部符号可用，所以现在两个引擎都有悬停。
     *
     * 注意：hover 上报频率高（跟随手指移动），这里不做节流会拖慢引擎，
     * 由调用方按帧节流。
     */
    public void hover(float decorX, float decorY) {
        if (target == TARGET_FVP) {
            // FVP：phase=1（move）就是引擎的鼠标移动通道，脚本侧 PrimHit 依赖它做按钮高亮。
            long h = fvpHandle();
            if (h != 0L && fvpHandleProvider != null) {
                try {
                    float[] p = fvpHandleProvider.toContentCoords(decorX, decorY, new float[2]);
                    NativeRfvp.touch(h, 1, p[0], p[1]);
                } catch (Throwable ignored) { }
            }
            return;
        }
        if (target == TARGET_ARTEMIS) {
            // 引擎未就绪时不必每帧重试（hover 是 16ms 一次的高频路径），
            // 但也不能永久禁用 —— 引擎初始化完成后必须能自动恢复。
            // 之前只在点击成功时解除熔断，导致"进游戏直接移动没悬停、
            // 先点一下再移动才有"这种时灵时不灵的表现。
            if (artemisHoverBackoffUntil > 0) {
                if (SystemClock.uptimeMillis() < artemisHoverBackoffUntil) return;
                artemisHoverBackoffUntil = 0;   // 退避到期，重试一次
            }
            if (!ArtemisNativeInput.move(Math.round(decorX), Math.round(decorY))) {
                artemisHoverBackoffUntil = SystemClock.uptimeMillis() + HOVER_RETRY_INTERVAL_MS;
                if (!artemisHoverWarned) {
                    artemisHoverWarned = true;
                    android.util.Log.w(TAG, "artemis native move unavailable, "
                            + "retrying every " + HOVER_RETRY_INTERVAL_MS + "ms");
                }
            } else if (artemisHoverWarned) {
                artemisHoverWarned = false;
                android.util.Log.i(TAG, "artemis native hover recovered");
            } else if (!artemisHoverConfirmed) {
                // 首次成功时打一条，便于确认悬停通道真的通了。
                // 之前 hover 全程无日志，"有时候能有时候不能"完全无法从日志判断。
                artemisHoverConfirmed = true;
                android.util.Log.i(TAG, "artemis native hover active");
            }
            return;
        }
        if (!(krkrSurfaceView instanceof android.view.View)) return;
        try {
            android.view.View view = (android.view.View) krkrSurfaceView;
            int[] loc = new int[2];
            view.getLocationInWindow(loc);
            org.tvp.kirikiri2.KR2Activity.nativeHoverMoved(decorX - loc[0], decorY - loc[1]);
        } catch (Throwable t) {
            // native 未就绪或该版本无此符号：降级为无悬停，不影响点击
            if (!hoverFailed) {
                hoverFailed = true;
                android.util.Log.w(TAG, "hover unavailable, disabled", t);
            }
        }
    }

    private boolean hoverFailed;
    /**
     * Artemis native hover 的退避重试时刻（uptimeMillis）。0 = 不在退避中。
     *
     * hover 每 16ms 一次，引擎未就绪时不该每帧白跑 JNI；但也不能永久关掉，
     * 因为 Artemis 是 NativeActivity，probe 在 Activity 起来时常常还是 1
     * （引擎未就绪），几百毫秒后才真正可用。用定期重试而非一次性熔断。
     */
    private long artemisHoverBackoffUntil;
    /** 只打一次警告，避免刷日志。恢复时也只打一次。 */
    private boolean artemisHoverWarned;
    /** 首次 hover 成功已记录，避免每帧刷日志。 */
    private boolean artemisHoverConfirmed;
    /**
     * Artemis native 通道「按下未抬起」标志。
     *
     * 用它代替固定时长节流：只要上一次已抬起就立刻允许下一次点击。
     * 固定阈值会误伤正常连点（实测 144ms 的两次点击被吞掉一次）。
     */
    private boolean artemisPressed;

    /**
     * FVP 通道「按下未抬起」标志（同 Artemis 思路：只要求上一次已抬起）。
     */
    private boolean fvpPressed;

    /**
     * 强制复位注入状态。
     *
     * 必要性：Artemis 的「按下」靠 postDelayed 抬起，如果游戏在这 120ms 内
     * 被切走或 overlay 被销毁，回调可能不执行，artemisPressed 就永久卡在 true，
     * 之后所有点击都会被静默丢弃。宿主生命周期变化时必须调这个。
     */
    public void reset() {
        // 游戏被切走再回来时，引擎状态可能已变，让 hover 立刻重试一次
        artemisHoverBackoffUntil = 0;
        if (fvpPressed) {
            // FVP 的抬起也靠 postDelayed：游戏若在这 50ms 内被切走，回调可能不执行，
            // 标志就会卡在 true 导致后续点击被静默丢弃。这里直接补一次 up + 复位。
            fvpPressed = false;
            long h = fvpHandle();
            if (h != 0L) {
                try { NativeRfvp.touch(h, 3, 0, 0); } catch (Throwable ignored) { }
            }
            android.util.Log.i(TAG, "injector reset: released stuck fvp press");
        }
        if (artemisPressed) {
            artemisPressed = false;
            if (target == TARGET_ARTEMIS) ArtemisNativeInput.release();
            android.util.Log.i(TAG, "injector reset: released stuck press");
        }
        // 无论有没有卡住的按下，都把状态清干净：
        // 抬起边沿（4）若残留会让引擎每帧都判定为"刚抬起"。
        if (target == TARGET_ARTEMIS) ArtemisNativeInput.clearKeys();
    }

    /** 悬停通道是否已被判定不可用。 */
    public boolean isHoverAvailable() {
        if (target == TARGET_ARTEMIS) return ArtemisNativeInput.probe() == 2;
        return !hoverFailed;
    }

    public void tap(float decorX, float decorY) {
        long now = SystemClock.uptimeMillis();
        // 节流只为防止合成 MotionEvent 冲爆引擎输入队列（KRKR 通道）。
        // native 直写通道不进任何队列，唯一约束是「上一次按下必须已抬起」，
        // 这由 artemisPressed 标志精确判定，不需要按时长挡 ——
        // 之前用 hold+30 的固定阈值会把 144ms 的正常连点也吞掉。
        if (target == TARGET_ARTEMIS) {
            if (artemisPressed) return;
        } else if (target == TARGET_FVP) {
            // FVP 走宿主 C ABI，没有输入队列积压问题，但引擎按帧读边沿，
            // 所以只要求「上一次按下已抬起」（同 Artemis 的思路，不按时长挡连点）。
            if (fvpPressed) return;
        } else {
            if (now - lastInjectAt < MIN_INJECT_INTERVAL_MS) return;
        }
        lastInjectAt = now;
        try {
            if (target == TARGET_FVP) {
                tapFvp(decorX, decorY);
            } else if (target == TARGET_ARTEMIS) {
                tapArtemis(decorX, decorY);
            } else {
                tapKrkr(decorX, decorY);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "tap failed", t);
        }
    }

    /**
     * FVP 点击：走 rfvp 宿主 C ABI（{@code rfvp_android_touch}），按下与抬起分两帧。
     *
     * <p>为什么按下/抬起要隔开：rfvp 的脚本每帧读 {@code InputGetDown} 边沿，
     * 同一帧内 down+up 会被合并成"无事发生"。这里保持 {@link #FVP_PRESS_HOLD_MS}
     * 再抬起，60fps 下至少跨 3 帧，掉帧也不丢。
     *
     * <p>为什么不走 {@code downSettleFrames}（FvpActivity 的防穿透机制）：
     * 那条路是给**手指直接触摸**用的（要在 down 前插一次完整 step 让脚本先看到光标）。
     * 鼠标模式下光标位置是持续 hover 上报的，脚本早已知道光标在哪，
     * 因此这里直接 down/up 即可，手感更跟手。
     */
    private void tapFvp(float decorX, float decorY) {
        final long h = fvpHandle();
        if (h == 0L) {
            android.util.Log.w(TAG, "fvp tap ignored: engine not ready");
            return;
        }
        fvpPressed = true;
        float[] p = fvpHandleProvider == null
                ? new float[]{decorX, decorY}
                : fvpHandleProvider.toContentCoords(decorX, decorY, new float[2]);
        final float cx = p[0], cy = p[1];
        NativeRfvp.touch(h, 0, cx, cy);                  // down
        final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(() -> {
            long h2 = fvpHandle();
            if (h2 != 0L) {
                NativeRfvp.touch(h2, 2, cx, cy);         // up
            }
            fvpPressed = false;
        }, FVP_PRESS_HOLD_MS);
        android.util.Log.d(TAG, "fvp tap (" + (int) cx + "," + (int) cy + ")");
    }

    /**
     * Artemis 点击：优先 native 通道，失败降级到无障碍手势。
     *
     * native 通道直接调引擎的 CInputBase::ReportPress，不经过系统输入派发，
     * 因此不受悬浮窗遮挡影响（无障碍那条路的多次失败都源于此）。
     * 按下与抬起要分开：引擎按帧读输入状态，同一帧内按下+抬起会被判为无事发生。
     */
    private void tapArtemis(float decorX, float decorY) {
        final int x = Math.round(decorX);
        final int y = Math.round(decorY);
        if (ArtemisNativeInput.press(x, y)) {
            // 点击成功说明引擎已就绪，立刻解除 hover 的退避
            artemisHoverBackoffUntil = 0;
            artemisPressed = true;
            // 保持足够长再抬起：引擎按帧轮询状态数组，太短会被掉帧漏掉。
            // 抬起分两步：先写抬起边沿让 IsUpEdge 成立，再延时清回空闲 ——
            // 引擎的 Execute() 在 deque 为空时不碰状态数组，不会帮我们清，
            // 卡在抬起边沿会让"拖到哪都算点击"。
            android.os.Handler h =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                ArtemisNativeInput.release();
                artemisPressed = false;
                // 再等一帧让引擎读到抬起边沿，然后清干净
                h.postDelayed(ArtemisNativeInput::clearKeys, ARTEMIS_UP_EDGE_HOLD_MS);
            }, ARTEMIS_PRESS_HOLD_MS);
            android.util.Log.d(TAG, "artemis native tap (" + x + "," + y + ")");
            return;
        }
        // native 不可用：退回无障碍手势
        if (accessibilityTap != null) {
            accessibilityTap.accept(x, y);
        } else {
            android.util.Log.w(TAG, "tap ignored: no native and no accessibility channel");
        }
    }

    /**
     * native 注入通道是否可用（仅 Artemis 有意义）。
     *
     * 可用时调用方不需要做任何窗口 hack：native 调用不经过系统输入派发。
     */
    public boolean isNativeChannelReady() {
        return target == TARGET_ARTEMIS && ArtemisNativeInput.probe() == 2;
    }

    /** Artemis 注入回调：由 GameCursorManager 提供（桥接无障碍服务）。 */
    public interface AccessibilityTap {
        void accept(int x, int y);
    }

    private AccessibilityTap accessibilityTap;

    public void setAccessibilityTap(AccessibilityTap tap) {
        this.accessibilityTap = tap;
    }

    private void tapKrkr(float decorX, float decorY) {
        if (!(krkrSurfaceView instanceof android.view.View)) {
            android.util.Log.w(TAG, "krkr surface view not ready");
            return;
        }
        android.view.View view = (android.view.View) krkrSurfaceView;
        // DecorView 坐标 → 内容 View 坐标：减去内容 View 在窗口内的偏移
        // （krkr 全屏但非刘海延伸时，内容 View 顶部会留出状态栏高度）。
        int[] loc = new int[2];
        view.getLocationInWindow(loc);
        float x = decorX - loc[0];
        float y = decorY - loc[1];

        long downTime = SystemClock.uptimeMillis();
        // 单指 12 参重载：downTime, eventTime, action, x, y, pressure, size, metaState, xPrecision, yPrecision, deviceId, edgeFlags
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                x, y, 1f, 1f, 0, 0f, 0f, 0, 0);
        view.dispatchTouchEvent(down);
        long upTime = downTime + TAP_DURATION_MS;
        MotionEvent up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP,
                x, y, 1f, 1f, 0, 0f, 0f, 0, 0);
        view.dispatchTouchEvent(up);
        android.util.Log.d(TAG, "krkr tap (" + (int) x + "," + (int) y + ")");
    }
}