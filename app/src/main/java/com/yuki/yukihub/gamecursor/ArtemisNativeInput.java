package com.yuki.yukihub.gamecursor;

/**
 * Artemis 引擎输入注入的 native 通道。
 *
 * 为什么需要它：Artemis 是 NativeActivity，输入由 native 的 AInputQueue 处理，
 * JNI 层零鼠标接口，无障碍手势试过多轮均无效（手势能派发成功但引擎不响应）。
 * 这里改为直接调用引擎内部的 CInputBase 上报函数 —— 从引擎内部喂输入，
 * 不经过系统输入派发，因此也不受悬浮窗遮挡、窗口 touchable 等问题影响。
 *
 * 实现在 app/src/main/cpp/artemis_input.c，通过 dlopen/dlsym 取引擎符号。
 *
 * 全部方法在库缺失或引擎未就绪时安全返回 false，绝不抛异常 ——
 * 调用方可以无条件调用，失败自动降级回无障碍通道。
 */
public final class ArtemisNativeInput {
    private static final String TAG = "YukiGameCursor";
    /** 0=未尝试 1=可用 2=不可用 */
    private static volatile int loadState;

    private ArtemisNativeInput() { }

    private static native int nativeProbe();

    private static native boolean nativeMove(int x, int y);

    private static native boolean nativeTap(int x, int y);

    private static native boolean nativeRelease();

    private static native boolean nativeClearKeys();

    private static native void nativeDumpKeys();

    /**
     * 诊断：把引擎当前所有非零的按键状态打进 logcat（标签 YukiArtemisNative）。
     *
     * 用来找出「鼠标左键/确认」对应的 key 索引 —— 那是引擎内部的键位枚举，
     * 从符号表推不出来。真实触摸屏幕时调用，引擎写了哪个槽位就能看到。
     */
    public static void dumpKeys() {
        if (!isLoaded()) return;
        try {
            nativeDumpKeys();
        } catch (Throwable ignored) { }
    }

    /** 库是否加载成功。第一次调用时才真正 loadLibrary，避免影响不用的进程。 */
    public static boolean isLoaded() {
        if (loadState != 0) return loadState == 1;
        synchronized (ArtemisNativeInput.class) {
            if (loadState != 0) return loadState == 1;
            try {
                System.loadLibrary("yukiartemisinput");
                loadState = 1;
                android.util.Log.i(TAG, "artemis native input loaded");
            } catch (Throwable t) {
                loadState = 2;
                android.util.Log.w(TAG, "artemis native input unavailable: " + t.getMessage());
            }
        }
        return loadState == 1;
    }

    /**
     * 探测引擎侧状态。
     *
     * @return 0=库/符号缺失 1=符号就绪但引擎未初始化完 2=完全可用；-1=本库未加载
     */
    public static int probe() {
        if (!isLoaded()) return -1;
        try {
            return nativeProbe();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "probe failed", t);
            return 0;
        }
    }

    /** 移动光标位置（即鼠标悬停）：引擎的按钮高亮读的就是这个位置。 */
    public static boolean move(int x, int y) {
        if (!isLoaded()) return false;
        try {
            return nativeMove(x, y);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按下（含定位）。需配合 release 构成完整点击。 */
    public static boolean press(int x, int y) {
        if (!isLoaded()) return false;
        try {
            return nativeTap(x, y);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "native tap failed", t);
            return false;
        }
    }

    /** 抬起：写抬起边沿（state=4），引擎下一帧读 IsUpEdge 时触发按钮确认。 */
    public static boolean release() {
        if (!isLoaded()) return false;
        try {
            return nativeRelease();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 清空按键状态（state=0）。必须在 release() 之后延时调用。
     *
     * 引擎的 Execute() 在输入 deque 为空时完全不碰状态数组
     * （反汇编可见 cbz 直接跳过），而我们是直写状态数组、从不排队，
     * 所以引擎永远不会帮我们把抬起边沿推进回空闲。
     * 不清的话 state 卡在 4，IsUpEdge 每帧为真 —— 实测表现为
     * "拖动鼠标到哪都算一次点击"，并持续干扰悬停判定。
     */
    public static boolean clearKeys() {
        if (!isLoaded()) return false;
        try {
            return nativeClearKeys();
        } catch (Throwable t) {
            return false;
        }
    }
}