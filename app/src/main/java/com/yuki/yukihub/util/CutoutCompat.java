package com.yuki.yukihub.util;

import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

/**
 * 刘海/挖孔区域（DisplayCutout）绘制兼容工具。
 * <p>
 * 应用默认全屏沉浸但从未设置 layoutInDisplayCutoutMode，Android 会把内容限制在安全区内，
 * 导致游戏画面/背景被刘海挤偏。这里提供统一的开关：
 * <ul>
 *   <li>API 28-29：LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES（横屏时延伸短边）</li>
 *   <li>API 30+：LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS（彻底延伸到所有挖孔区）</li>
 *   <li>关闭时回退到系统默认/NEVER</li>
 * </ul>
 * API 28 以下无刘海概念，直接忽略。
 */
public final class CutoutCompat {

    private CutoutCompat() { }

    /** 允许（或禁止）内容绘制到刘海/挖孔区域。 */
    public static void setCutoutMode(Window window, boolean allow) {
        if (window == null || Build.VERSION.SDK_INT < 28) return;
        try {
            WindowManager.LayoutParams lp = window.getAttributes();
            if (Build.VERSION.SDK_INT >= 30) {
                lp.layoutInDisplayCutoutMode = allow
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            } else {
                lp.layoutInDisplayCutoutMode = allow
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            }
            window.setAttributes(lp);
        } catch (Throwable ignored) { }
    }
}
