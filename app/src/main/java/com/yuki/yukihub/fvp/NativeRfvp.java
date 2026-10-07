package com.yuki.yukihub.fvp;

import android.content.Context;
import android.view.Surface;

/**
 * JNI 桥：驱动 FVP 引擎（rfvp）的 Android 宿主接口。
 *
 * <p>rfvp 是「宿主驱动」引擎：Rust 侧（librfvp.so）只导出一组 {@code rfvp_android_*} C ABI，
 * Surface 生命周期与逐帧推进都由 Java 宿主负责。{@code libyukirfvp.so} 负责
 * dlopen librfvp.so 并转发调用（实现见 {@code app/src/main/cpp/rfvp_bridge.c}），
 * 本类的 native 方法即宿主与引擎之间的唯一契约。
 *
 * <p>所有方法在库缺失/引擎未就绪时都安全返回（句柄 0 即无效），绝不抛异常。
 */
public final class NativeRfvp {

    static {
        // 只加载桥；librfvp.so 由桥 dlopen（与上游 rfvp 的做法一致）。
        System.loadLibrary("yukirfvp");
    }

    private NativeRfvp() { }

    /** ndk-context 是否已初始化（音频后端在 create 时依赖它，重复调用无副作用）。 */
    private static boolean sContextInited = false;

    /**
     * 初始化 ndk-context。必须在 {@link #create} 之前调用。
     * 内部做一次性保护：重复调用直接返回。
     */
    public static synchronized void initAndroidContext(Context context) {
        if (sContextInited || context == null) return;
        try {
            nativeInitAndroidContext(context.getApplicationContext());
            sContextInited = true;
        } catch (Throwable ignored) {
            // 引擎库缺失时静默失败：create 会返回 0，由上层提示启动失败。
        }
    }

    private static native void nativeInitAndroidContext(Context appContext);

    /** 创建引擎实例并绑定 Surface；失败返回 0。 */
    public static native long create(
            Surface surface,
            int widthPx,
            int heightPx,
            double nativeScaleFactor,
            String gameDirUtf8,
            String nlsUtf8);

    /** 步进一帧；返回非 0 表示引擎请求退出。 */
    public static native int step(long handle, int dtMs);

    /** Surface 尺寸变化（物理像素）。 */
    public static native void resize(long handle, int widthPx, int heightPx);

    /** 重新绑定 ANativeWindow（SurfaceView 重建 / 切后台回来时使用）。 */
    public static native void setSurface(long handle, Surface surface, int widthPx, int heightPx);

    /** 注入单指触摸事件（坐标为物理像素）。phase 0/1/2/3 = down/move/up/cancel。 */
    public static native void touch(long handle, int phase, double xPx, double yPx);

    /** 文本高分辨率渲染开关（中文/日文小字更清晰）。 */
    public static native void setTextHidpi(long handle, boolean enabled);

    /**
     * 全局文字缩放系数（1.0 = 脚本原大；1.5 = 文字放大 50%）。
     * 仅当 librfvp.so 为 YukiHub 补丁版（导出 rfvp_android_set_text_scale）时生效，
     * 旧版引擎没有该符号时本调用安全无害。
     */
    public static native void setTextScale(long handle, float scale);

    /**
     * 行距随字号的跟随程度（1.0 = 与字号等比 = 引擎原行为；0.5 = 放大增量减半；
     * 0.0 = 行高保持脚本原值）。只影响行与行之间的距离，字形大小不变。
     * 仅当 librfvp.so 为 YukiHub 补丁版（导出 rfvp_android_set_text_line_scale）时生效。
     */
    public static native void setTextLineScale(long handle, float scale);

    /**
     * 注入按键事件（Windows VK 语义）。keyCode 0x1B = Escape、0x0D = Enter、0x20 = Space、
     * 0x25..0x28 = 方向键；phase 0 = down、1 = up。
     */
    public static native void keyEvent(long handle, int keyCode, int phase);

    /** 系统 CJK 字体回退开关（开启后触发一次性系统字体扫描）。 */
    public static native void setSystemFont(long handle, boolean enabled);

    /** 追加字体文件并返回字体 id（≥0 成功；-1 失败）。 */
    public static native int addFont(long handle, String fontPathUtf8);

    /** 设置/清除强制默认字体（fontId &lt; 0 清除）。 */
    public static native void setForcedFont(long handle, int fontId);

    /** 销毁实例。 */
    public static native void destroy(long handle);
}