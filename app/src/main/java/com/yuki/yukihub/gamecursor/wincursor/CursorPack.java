package com.yuki.yukihub.gamecursor.wincursor;

import android.graphics.Bitmap;

/**
 * 一个完整的光标（静态单帧或动画多帧）。
 */
public final class CursorPack {
    /** 帧列表，至少一项。 */
    public final CursorFrame[] frames;
    /** 播放序列：元素是 frames 的下标。ani 的 seq chunk 允许帧复用。 */
    public final int[] sequence;
    /** 序列中每一步的时长（毫秒），与 sequence 等长。 */
    public final int[] stepDurations;

    public CursorPack(CursorFrame[] frames, int[] sequence, int[] stepDurations) {
        this.frames = frames;
        this.sequence = sequence;
        this.stepDurations = stepDurations;
    }

    public boolean isAnimated() {
        return sequence != null && sequence.length > 1;
    }

    public CursorFrame firstFrame() {
        return frames != null && frames.length > 0 ? frames[0] : null;
    }

    /** 总时长（毫秒），静态为 0。 */
    public int totalDurationMs() {
        if (stepDurations == null) return 0;
        int t = 0;
        for (int d : stepDurations) t += d;
        return t;
    }

    public void recycle() {
        if (frames == null) return;
        for (CursorFrame f : frames) {
            if (f != null && f.bitmap != null && !f.bitmap.isRecycled()) f.bitmap.recycle();
        }
    }
}