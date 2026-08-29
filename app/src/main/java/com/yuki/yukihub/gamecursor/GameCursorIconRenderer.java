package com.yuki.yukihub.gamecursor;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * 内置光标的唯一绘制来源：游戏内光标层与设置界面预览调用同一份代码，
 * 保证所见即所得。
 *
 * 形状取自用户提供的 SVG（viewBox 1024×1024）内轮廓，是个干净的 7 点多边形，
 * 尖端正好落在路径原点。这里把它归一化到 0..1 再按视图尺寸缩放，
 * 因此任意大小下形状比例都不变形。
 */
public final class GameCursorIconRenderer {
    /**
     * 形状宽高比 = 484.864 / 791.936（SVG 内轮廓包围盒）。
     * 视图宽 = 视图高 × ASPECT。
     */
    public static final float ASPECT = 0.6123f;

    /**
     * 尖端在视图内的相对位置。留出这点边距是为了让黑色描边不被裁掉；
     * 注入点击时必须用这个比例换算真实尖端坐标，否则点击点会和视觉尖端错位。
     */
    public static final float TIP_RATIO = 0.05f;

    /** 归一化顶点（尖端为 (0,0)，右下方向展开）。 */
    private static final float[] PTS = {
            0.00000f, 0.00000f,   // 尖端
            1.00000f, 0.55690f,   // 右侧最远点
            0.51571f, 0.57920f,   // 内折
            0.80029f, 0.95313f,   // 尾巴右下
            0.63627f, 1.00000f,   // 尾巴左下
            0.34700f, 0.61969f,   // 收回
            0.00000f, 0.83198f,   // 左侧下端
    };

    private GameCursorIconRenderer() { }

    /**
     * 指针路径，绘制在 (0,0)-(w,h) 内，尖端位于 (w*TIP_RATIO, h*TIP_RATIO)。
     */
    public static Path buildArrowPath(float w, float h) {
        float sx = w * (1f - 2f * TIP_RATIO);
        float sy = h * (1f - 2f * TIP_RATIO);
        float ox = w * TIP_RATIO;
        float oy = h * TIP_RATIO;
        Path p = new Path();
        p.moveTo(ox + PTS[0] * sx, oy + PTS[1] * sy);
        for (int i = 2; i < PTS.length; i += 2) {
            p.lineTo(ox + PTS[i] * sx, oy + PTS[i + 1] * sy);
        }
        p.close();
        return p;
    }

    /**
     * 把指针画到 canvas 的 (0,0)-(w,h) 区域。
     * pressed=true 时填充变浅黄并在尖端加高亮环，指示实际点击位置。
     */
    public static void draw(Canvas canvas, float w, float h, boolean pressed) {
        if (w <= 0 || h <= 0) return;
        Path path = buildArrowPath(w, h);
        float stroke = Math.max(1.5f, w * 0.07f);

        // 投影：往右下偏一点，暗背景上也能拉开层次
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setStyle(Paint.Style.FILL);
        shadow.setColor(0x40000000);
        int sc = canvas.save();
        canvas.translate(stroke * 0.7f, stroke * 0.7f);
        canvas.drawPath(path, shadow);
        canvas.restoreToCount(sc);

        // 黑色描边（对应 SVG 的 #333333 轮廓）
        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(stroke);
        outline.setStrokeJoin(Paint.Join.MITER);
        outline.setStrokeMiter(4f);
        outline.setColor(0xFF333333);
        canvas.drawPath(path, outline);

        // 白色填充（保证在暗背景上也醒目）
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(pressed ? 0xFFFFE9A8 : 0xFFFFFFFF);
        canvas.drawPath(path, fill);

        if (pressed) {
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Math.max(1.5f, w * 0.06f));
            ring.setColor(0xCCFF5A5A);
            canvas.drawCircle(w * TIP_RATIO, h * TIP_RATIO, w * 0.2f, ring);
        }
    }
}