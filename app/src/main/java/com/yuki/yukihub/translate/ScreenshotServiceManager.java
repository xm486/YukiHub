package com.yuki.yukihub.translate;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.RectF;

/**
 * 管理翻译无障碍截图服务实例，并向悬浮翻译服务转发截图请求。
 *
 * 适配说明：参考 MoeTranslate 的 AccessibilityServiceManager，
 * 由 Kotlin object 转换为 Java 静态管理器。
 */
public final class ScreenshotServiceManager {
    private static volatile YukiScreenshotAccessibilityService service;

    private ScreenshotServiceManager() {
    }

    public static void setService(YukiScreenshotAccessibilityService value) {
        service = value;
    }

    public static YukiScreenshotAccessibilityService getService() {
        return service;
    }

    public static boolean isReady() {
        return service != null;
    }

    public static void takeScreenshot(RectF cropRect, Point offset,
                                      YukiScreenshotAccessibilityService.Callback callback) {
        YukiScreenshotAccessibilityService current = service;
        if (current == null) {
            if (callback != null) callback.onFailure("无障碍截图服务尚未连接");
            return;
        }
        current.takeScreenshot(cropRect, offset, callback);
    }

    /** 释放调用方持有的截图 Bitmap。 */
    public static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}