package com.yuki.yukihub.ui.colorpicker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 自定义颜色选择器视图
 * 包含色相环、饱和度/亮度选择器、透明度滑块
 */
public class ColorPickerView extends View {
    
    private static final int HUE_RING_WIDTH = 30;
    private static final int SV_RECT_PADDING = 20;
    private static final int ALPHA_SLIDER_HEIGHT = 30;
    private static final int PADDING = 10;
    
    private Paint huePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint svPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint alphaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    
    private RectF hueRect = new RectF();
    private RectF svRect = new RectF();
    private RectF alphaRect = new RectF();
    
    private float hue = 0f; // 0-360
    private float saturation = 1f; // 0-1
    private float value = 1f; // 0-1
    private int alpha = 255; // 0-255
    
    private OnColorChangeListener listener;
    
    public ColorPickerView(Context context) {
        super(context);
        init();
    }
    
    public ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public ColorPickerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        indicatorPaint.setStyle(Paint.Style.STROKE);
        indicatorPaint.setStrokeWidth(3f);
        indicatorPaint.setColor(Color.WHITE);
        
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1f);
        borderPaint.setColor(0x44FFFFFF);
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateRects(w, h);
    }
    
    private void updateRects(int w, int h) {
        int availableWidth = w - PADDING * 2;
        int availableHeight = h - PADDING * 2;
        
        // 色相环（左侧竖条）
        hueRect.set(PADDING, PADDING, PADDING + HUE_RING_WIDTH, PADDING + availableHeight);
        
        // 饱和度/亮度选择器（右侧大方块）
        int svSize = Math.min(availableWidth - HUE_RING_WIDTH - SV_RECT_PADDING, 
                             availableHeight - ALPHA_SLIDER_HEIGHT - SV_RECT_PADDING);
        svRect.set(PADDING + HUE_RING_WIDTH + SV_RECT_PADDING, PADDING,
                  PADDING + HUE_RING_WIDTH + SV_RECT_PADDING + svSize,
                  PADDING + svSize);
        
        // 透明度滑块（底部）
        alphaRect.set(PADDING + HUE_RING_WIDTH + SV_RECT_PADDING,
                     svRect.bottom + SV_RECT_PADDING,
                     svRect.right,
                     svRect.bottom + SV_RECT_PADDING + ALPHA_SLIDER_HEIGHT);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 绘制色相环
        drawHueBar(canvas);
        
        // 绘制饱和度/亮度选择器
        drawSVRect(canvas);
        
        // 绘制透明度滑块
        drawAlphaSlider(canvas);
        
        // 绘制指示器
        drawIndicators(canvas);
    }
    
    private void drawHueBar(Canvas canvas) {
        float[] hsv = new float[3];
        for (int i = 0; i < hueRect.height(); i++) {
            hsv[0] = (i / hueRect.height()) * 360f;
            hsv[1] = 1f;
            hsv[2] = 1f;
            huePaint.setColor(Color.HSVToColor(hsv));
            canvas.drawLine(hueRect.left, hueRect.top + i, hueRect.right, hueRect.top + i, huePaint);
        }
        canvas.drawRect(hueRect, borderPaint);
    }
    
    private void drawSVRect(Canvas canvas) {
        // 绘制饱和度渐变（水平，从白色到当前色相的颜色）
        float[] hsv = new float[]{hue, 1f, 1f};
        int pureColor = Color.HSVToColor(hsv);
        
        LinearGradient saturationGradient = new LinearGradient(
                svRect.left, svRect.top, svRect.right, svRect.top,
                0xFFFFFFFF, pureColor, Shader.TileMode.CLAMP);
        svPaint.setShader(saturationGradient);
        canvas.drawRect(svRect, svPaint);
        
        // 绘制亮度渐变（垂直，从半透明白色到半透明黑色）
        LinearGradient valueGradient = new LinearGradient(
                svRect.left, svRect.top, svRect.left, svRect.bottom,
                0x00FFFFFF, 0xFF000000, Shader.TileMode.CLAMP);
        svPaint.setShader(valueGradient);
        canvas.drawRect(svRect, svPaint);
        
        canvas.drawRect(svRect, borderPaint);
    }
    
    private void drawAlphaSlider(Canvas canvas) {
        // 先绘制棋盘格背景（表示透明度）
        drawCheckerboard(canvas, alphaRect);
        
        // 再绘制当前颜色从透明到不透明的渐变
        int rgb = Color.HSVToColor(new float[]{hue, saturation, value}) & 0x00FFFFFF;
        LinearGradient alphaGradient = new LinearGradient(
                alphaRect.left, alphaRect.top, alphaRect.right, alphaRect.top,
                rgb, 0xFF000000 | rgb, Shader.TileMode.CLAMP);
        alphaPaint.setShader(alphaGradient);
        canvas.drawRect(alphaRect, alphaPaint);
        alphaPaint.setShader(null);
        
        canvas.drawRect(alphaRect, borderPaint);
    }
    
    private void drawCheckerboard(Canvas canvas, RectF rect) {
        Paint checkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        checkerPaint.setColor(0x88888888);
        
        int checkSize = 10;
        for (int x = (int) rect.left; x < rect.right; x += checkSize) {
            for (int y = (int) rect.top; y < rect.bottom; y += checkSize) {
                if ((x / checkSize + y / checkSize) % 2 == 0) {
                    canvas.drawRect(x, y, x + checkSize, y + checkSize, checkerPaint);
                }
            }
        }
    }
    
    private void drawIndicators(Canvas canvas) {
        // 色相指示器
        float hueY = hueRect.top + (hue / 360f) * hueRect.height();
        canvas.drawLine(hueRect.left - 5, hueY, hueRect.right + 5, hueY, indicatorPaint);
        
        // 饱和度/亮度指示器
        float svX = svRect.left + saturation * svRect.width();
        float svY = svRect.top + (1f - value) * svRect.height();
        canvas.drawCircle(svX, svY, 8, indicatorPaint);
        
        // 透明度指示器
        float alphaX = alphaRect.left + (alpha / 255f) * alphaRect.width();
        canvas.drawLine(alphaX, alphaRect.top - 5, alphaX, alphaRect.bottom + 5, indicatorPaint);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (hueRect.contains(x, y)) {
                    // 更新色相
                    hue = ((y - hueRect.top) / hueRect.height()) * 360f;
                    hue = Math.max(0, Math.min(360, hue));
                    invalidate();
                    notifyColorChanged();
                    return true;
                } else if (svRect.contains(x, y)) {
                    // 更新饱和度/亮度
                    saturation = (x - svRect.left) / svRect.width();
                    value = 1f - (y - svRect.top) / svRect.height();
                    saturation = Math.max(0, Math.min(1, saturation));
                    value = Math.max(0, Math.min(1, value));
                    invalidate();
                    notifyColorChanged();
                    return true;
                } else if (alphaRect.contains(x, y)) {
                    // 更新透明度
                    alpha = (int) (((x - alphaRect.left) / alphaRect.width()) * 255);
                    alpha = Math.max(0, Math.min(255, alpha));
                    invalidate();
                    notifyColorChanged();
                    return true;
                }
                break;
        }
        return super.onTouchEvent(event);
    }
    
    private void notifyColorChanged() {
        if (listener != null) {
            int color = getColor();
            listener.onColorChanged(color, hue, saturation, value, alpha);
        }
    }
    
    public int getColor() {
        float[] hsv = new float[]{hue, saturation, value};
        int rgb = Color.HSVToColor(hsv);
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
    
    public void setColor(int color) {
        // 提取HSV值
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        
        // 提取透明度
        alpha = Color.alpha(color);
        
        invalidate();
    }
    
    public void setOnColorChangeListener(OnColorChangeListener listener) {
        this.listener = listener;
    }
    
    public interface OnColorChangeListener {
        void onColorChanged(int color, float hue, float saturation, float value, int alpha);
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = 400;
        int desiredHeight = 400;
        
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        
        int width, height;
        
        if (widthMode == MeasureSpec.EXACTLY) {
            width = widthSize;
        } else if (widthMode == MeasureSpec.AT_MOST) {
            width = Math.min(desiredWidth, widthSize);
        } else {
            width = desiredWidth;
        }
        
        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            height = Math.min(desiredHeight, heightSize);
        } else {
            height = desiredHeight;
        }
        
        setMeasuredDimension(width, height);
    }
}