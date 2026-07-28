package com.yuki.yukihub.translate;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Android 11+ 无障碍截图服务。
 *
 * 仅负责将系统截图转换为可变 ARGB_8888 Bitmap，并按可选区域裁剪；
 * OCR、图片缓存和翻译由后续模块负责。
 *
 * 适配说明：参考 MoeTranslate 的 ScreenShotAccessibilityService，
 * 已将协程/SharedFlow 改为 Java 回调，并保留原 LGPL 适配要求。
 */
public class YukiScreenshotAccessibilityService extends AccessibilityService {
    private static final String TAG = "YukiScreenshotService";

    public interface Callback {
        void onSuccess(Bitmap bitmap);
        void onFailure(String message);
    }

    public void takeScreenshot(RectF cropRect, Point offset, Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            fail(callback, "屏幕翻译需要 Android 11 或更高版本");
            return;
        }
        try {
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult screenshot) {
                            processScreenshot(screenshot, cropRect, offset, callback);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            fail(callback, screenshotError(errorCode));
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "takeScreenshot failed", t);
            fail(callback, "系统截图失败：" + safeMessage(t));
        }
    }

    private void processScreenshot(ScreenshotResult result, RectF cropRect,
                                   Point offset, Callback callback) {
        HardwareBuffer hardwareBuffer = result == null ? null : result.getHardwareBuffer();
        Bitmap bitmap = null;
        try {
            if (hardwareBuffer == null) {
                fail(callback, "系统没有返回截图缓冲区");
                return;
            }
            Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(
                    hardwareBuffer, result.getColorSpace());
            if (hardwareBitmap == null) {
                fail(callback, "无法读取系统截图");
                return;
            }
            bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true);
            if (bitmap == null) {
                fail(callback, "无法转换截图格式");
                return;
            }
            if (cropRect != null) {
                Point safeOffset = offset == null ? new Point(0, 0) : offset;
                Bitmap cropped = cropBitmap(bitmap, cropRect, safeOffset);
                if (cropped == null) {
                    fail(callback, "截图区域无效或超出屏幕范围");
                    return;
                }
                if (cropped != bitmap) {
                    bitmap.recycle();
                    bitmap = cropped;
                }
            }
            Bitmap deliver = bitmap;
            bitmap = null;
            if (callback != null) callback.onSuccess(deliver);
        } catch (Throwable t) {
            Log.e(TAG, "process screenshot failed", t);
            fail(callback, "截图处理失败：" + safeMessage(t));
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (hardwareBuffer != null) hardwareBuffer.close();
        }
    }

    private Bitmap cropBitmap(Bitmap source, RectF cropRect, Point offset) {
        int left = Math.round(cropRect.left) + offset.x;
        int top = Math.round(cropRect.top) + offset.y;
        int right = Math.round(cropRect.right) + offset.x;
        int bottom = Math.round(cropRect.bottom) + offset.y;
        Rect bounds = new Rect(0, 0, source.getWidth(), source.getHeight());
        Rect requested = new Rect(left, top, right, bottom);
        if (!requested.intersect(bounds) || requested.width() <= 0 || requested.height() <= 0) {
            return null;
        }
        return Bitmap.createBitmap(source, requested.left, requested.top,
                requested.width(), requested.height());
    }

    private void fail(Callback callback, String message) {
        String text = message == null || message.trim().isEmpty() ? "截图失败" : message;
        if (callback != null) callback.onFailure(text);
        try {
            Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private String screenshotError(int errorCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "屏幕翻译需要 Android 11 或更高版本";
        switch (errorCode) {
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR:
                return "截图失败：系统内部错误";
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
                return "截图失败：无障碍权限不可用";
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                return "截图过于频繁，请稍后再试";
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY:
                return "截图失败：无效屏幕";
            default:
                return "截图失败，错误码：" + errorCode;
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        ScreenshotServiceManager.setService(this);
        Log.d(TAG, "Accessibility screenshot service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 当前阶段不处理无障碍事件，仅使用截图 API。
    }

    @Override
    public void onInterrupt() {
        // 系统中断时由 onDestroy 清除服务引用。
    }

    @Override
    public void onDestroy() {
        ScreenshotServiceManager.setService(null);
        super.onDestroy();
    }
}