package com.yuki.yukihub.gamecursor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

import com.yuki.yukihub.gamecursor.wincursor.CursorPack;
import com.yuki.yukihub.gamecursor.wincursor.WinCursorSupport;

/**
 * 光标外观的唯一实现来源。
 *
 * 存在的理由：之前游戏内 CursorView 会加载自定义图，而设置界面的预览
 * 只画内置箭头，两边行为不一致 —— 换了图在预览里看不出区别，
 * 进游戏才发现。现在两边都走这个类，不可能再不一致。
 *
 * 三种外观按优先级：
 *   1. Windows 光标包（.ani/.cur）—— 可动画，热点来自文件本身
 *   2. 自定义 PNG —— 静态，热点用默认值
 *   3. 内置箭头 —— 静态，热点是 TIP_RATIO
 */
public final class CursorAppearance {
    private static final String TAG = "YukiGameCursor";

    /** 光标包（已安装时非 null）。 */
    private CursorPack pack;
    /** 自定义 PNG（无光标包且已选图时非 null）。 */
    private Bitmap png;
    /** 当前播放到序列的哪一步。 */
    private int step;

    private final RectF dst = new RectF();

    /**
     * 按配置加载外观。会释放上一次的资源。
     *
     * @return true 表示用的是光标包或 PNG，false 表示回落到内置箭头
     */
    public boolean load(Context ctx, GameCursorConfig config) {
        release();
        if (config != null && config.hasWinCursor()) {
            pack = WinCursorSupport.loadInstalled(ctx);
            if (pack != null && pack.frames != null && pack.frames.length > 0) {
                android.util.Log.i(TAG, "appearance = win cursor pack, frames="
                        + pack.frames.length + " name=" + config.winCursorName);
                return true;
            }
            // 配置说装了包但缓存读不出来：说明缓存被清了或写入时就失败了。
            // 这种不一致必须能从日志看出来，否则只会表现为"莫名回落到箭头"。
            android.util.Log.w(TAG, "win cursor configured (" + config.winCursorName
                    + ") but cache load failed, falling back");
            pack = null;
        }
        if (config != null) {
            png = config.loadIcon(ctx);
            if (png != null) {
                android.util.Log.i(TAG, "appearance = custom png " + png.getWidth()
                        + "x" + png.getHeight());
                return true;
            }
            if (config.iconUri != null && !config.iconUri.trim().isEmpty()) {
                android.util.Log.w(TAG, "png configured but decode failed: " + config.iconUri);
            }
        }
        android.util.Log.i(TAG, "appearance = builtin arrow");
        return false;
    }

    public void release() {
        if (pack != null) {
            pack.recycle();
            pack = null;
        }
        if (png != null && !png.isRecycled()) {
            png.recycle();
        }
        png = null;
        step = 0;
    }

    public boolean isAnimated() {
        return pack != null && pack.isAnimated();
    }

    /** 当前帧的显示时长（毫秒）。非动画返回 0。 */
    public int currentDurationMs() {
        if (pack == null || pack.stepDurations == null || pack.stepDurations.length == 0) return 0;
        return pack.stepDurations[step % pack.stepDurations.length];
    }

    /** 推进到下一帧。 */
    public void advance() {
        if (pack == null || pack.sequence == null || pack.sequence.length == 0) return;
        step = (step + 1) % pack.sequence.length;
    }

    public void resetAnimation() {
        step = 0;
    }

    /**
     * 热点在图内的相对位置（0..1）。
     *
     * 这是「鼠标尖」在图上的位置，决定点击注入到哪个坐标。
     * 光标包用文件里的真实热点；其它情况用内置箭头的 TIP_RATIO。
     */
    public float hotspotX() {
        if (pack != null) {
            com.yuki.yukihub.gamecursor.wincursor.CursorFrame f = currentFrame();
            if (f != null) return f.hotspotX;
        }
        if (png != null) return 0f;   // 自定义 PNG 约定左上角为尖端
        return GameCursorIconRenderer.TIP_RATIO;
    }

    public float hotspotY() {
        if (pack != null) {
            com.yuki.yukihub.gamecursor.wincursor.CursorFrame f = currentFrame();
            if (f != null) return f.hotspotY;
        }
        if (png != null) return 0f;
        return GameCursorIconRenderer.TIP_RATIO;
    }

    /** 内容宽高比（宽/高），用于决定视图尺寸。 */
    public float aspect() {
        com.yuki.yukihub.gamecursor.wincursor.CursorFrame f = currentFrame();
        if (f != null && f.bitmap != null && f.bitmap.getHeight() > 0) {
            return f.bitmap.getWidth() / (float) f.bitmap.getHeight();
        }
        if (png != null && png.getHeight() > 0) {
            return png.getWidth() / (float) png.getHeight();
        }
        return GameCursorIconRenderer.ASPECT;
    }

    private com.yuki.yukihub.gamecursor.wincursor.CursorFrame currentFrame() {
        if (pack == null || pack.frames == null || pack.frames.length == 0) return null;
        if (pack.sequence == null || pack.sequence.length == 0) return pack.frames[0];
        int idx = pack.sequence[step % pack.sequence.length];
        if (idx < 0 || idx >= pack.frames.length) return pack.frames[0];
        return pack.frames[idx];
    }

    /**
     * 绘制。
     *
     * @param pressed 按下反馈。光标包/PNG 用整体缩放表示（对任意图都成立），
     *                内置箭头用它原来的换色高亮。
     */
    public void draw(Canvas canvas, float w, float h, boolean pressed) {
        com.yuki.yukihub.gamecursor.wincursor.CursorFrame f = currentFrame();
        Bitmap bmp = f != null ? f.bitmap : png;
        if (bmp == null || bmp.isRecycled()) {
            GameCursorIconRenderer.draw(canvas, w, h, pressed);
            return;
        }
        int sc = canvas.save();
        if (pressed) {
            // 以热点为轴心缩到 92%：Windows 光标格式没有"按下态"这个概念，
            // 点击反馈只能自己做。绕热点缩放能保证尖端位置不动。
            canvas.scale(0.92f, 0.92f, w * hotspotX(), h * hotspotY());
        }
        dst.set(0, 0, w, h);
        canvas.drawBitmap(bmp, null, dst, null);
        canvas.restoreToCount(sc);
    }
}