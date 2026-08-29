package com.yuki.yukihub.gamecursor.wincursor;

import android.graphics.Bitmap;

/**
 * 解码后的 Windows 光标（静态 .cur 或动画 .ani 的一帧）。
 *
 * 热点用**相对坐标**（0..1）保存而不是像素：光标在屏上会被缩放，
 * 相对坐标缩放无关，直接乘视图尺寸就是尖端位置。
 */
public final class CursorFrame {
    public final Bitmap bitmap;
    /** 热点相对位置（0..1），即"这个图的哪一点是鼠标尖"。 */
    public final float hotspotX;
    public final float hotspotY;
    /** 本帧显示时长（毫秒）。静态光标为 0。 */
    public final int durationMs;

    public CursorFrame(Bitmap bitmap, float hotspotX, float hotspotY, int durationMs) {
        this.bitmap = bitmap;
        this.hotspotX = hotspotX;
        this.hotspotY = hotspotY;
        this.durationMs = durationMs;
    }
}