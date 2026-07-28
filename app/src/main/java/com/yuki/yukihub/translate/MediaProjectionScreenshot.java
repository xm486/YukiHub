package com.yuki.yukihub.translate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * MediaProjection 备用截图方案。
 *
 * 适配说明：参考 MoeTranslate 的设计思路（萌译主要使用无障碍服务截图，
 * 但计划书中列出了 MediaProjection 作为备选方案），YukiHub 独立实现。
 *
 * 使用方式：
 *   1. Activity 中调用 createScreenCaptureIntent() 获取系统授权 Intent
 *   2. onActivityResult 中调用 startProjection(resultCode, data) 启动截图会话
 *   3. 调用 takeScreenshot(rect, offset, callback) 截取指定区域
 *   4. 不再使用时调用 release() 释放资源
 *
 * 注意：MediaProjection 需要 Android 5.0+（API 21+），YukiHub 目标为 API 33。
 * 需要前台服务运行期间使用。
 */
public final class MediaProjectionScreenshot {

    private static final String TAG = "MediaProjection";
    private static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

    private final Context context;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private boolean initialized = false;

    public MediaProjectionScreenshot(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 创建系统屏幕截图授权 Intent，由 Activity 启动。
     */
    public Intent createScreenCaptureIntent() {
        MediaProjectionManager mgr = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return mgr.createScreenCaptureIntent();
    }

    /**
     * 启动 MediaProjection 截图会话。
     * 必须在 Activity.onActivityResult 中调用，传入用户授权结果。
     */
    public void startProjection(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Projection permission denied");
            return;
        }

        MediaProjectionManager mgr = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mgr.getMediaProjection(resultCode, data);

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection");
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "YukiHubScreenshot",
                screenWidth, screenHeight, screenDensity,
                VIRTUAL_DISPLAY_FLAGS,
                imageReader.getSurface(), null, null
        );

        initialized = true;
        Log.d(TAG, "Projection started: " + screenWidth + "x" + screenHeight + " @" + screenDensity);
    }

    /**
     * 截取指定区域。
     *
     * @param rect     屏幕区域坐标（相对于屏幕左上角）
     * @param offset   截图偏移量（与无障碍截图方案兼容）
     * @param callback  截图回调
     */
    public void takeScreenshot(RectF rect, Point offset, final YukiScreenshotAccessibilityService.Callback callback) {
        if (!initialized || imageReader == null) {
            if (callback != null) callback.onFailure("MediaProjection not initialized");
            return;
        }

        // 获取最新 Image
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
        } catch (Exception e) {
            Log.e(TAG, "acquireLatestImage failed", e);
        }

        if (image == null) {
            // 尝试 acquireNextImage
            try {
                image = imageReader.acquireNextImage();
            } catch (Exception e) {
                Log.e(TAG, "acquireNextImage failed", e);
            }
        }

        if (image == null) {
            if (callback != null) callback.onFailure("No image available");
            return;
        }

        try {
            Bitmap fullBitmap = imageToBitmap(image);
            image.close();

            if (fullBitmap == null) {
                if (callback != null) callback.onFailure("Failed to convert image to bitmap");
                return;
            }

            // 计算裁剪区域
            int left = Math.max(0, (int) rect.left);
            int top = Math.max(0, (int) rect.top);
            int right = Math.min(screenWidth, (int) rect.right);
            int bottom = Math.min(screenHeight, (int) rect.bottom);
            int w = right - left;
            int h = bottom - top;

            if (w <= 0 || h <= 0) {
                if (callback != null) callback.onFailure("Invalid crop rect");
                return;
            }

            Bitmap cropped = Bitmap.createBitmap(fullBitmap, left, top, w, h);
            // 如果原图需要回收（fullBitmap != cropped）
            if (cropped != fullBitmap && !fullBitmap.isRecycled()) {
                fullBitmap.recycle();
            }

            if (callback != null) callback.onSuccess(cropped);
        } catch (Exception e) {
            Log.e(TAG, "Screenshot processing failed", e);
            try {
                image.close();
            } catch (Exception ignored) {
            }
            if (callback != null) callback.onFailure(e.getMessage());
        }
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) return null;

            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            int bmpWidth = screenWidth + rowPadding / pixelStride;
            Bitmap bmp = Bitmap.createBitmap(bmpWidth, screenHeight, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            bmp.copyPixelsFromBuffer(buffer);

            // 如果有 padding，裁剪到实际屏幕大小
            if (bmpWidth > screenWidth) {
                Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight);
                bmp.recycle();
                return cropped;
            }
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "imageToBitmap failed", e);
            return null;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void release() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        initialized = false;
    }
}