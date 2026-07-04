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
 * 渐变色选择器视图
 * 支持双色渐变，可调整渐变方向和颜色位置
 */
public class GradientPickerView extends View {
    
    private static final int PREVIEW_HEIGHT = 60;
    private static final int CONTROL_HEIGHT = 40;
    private static final int PADDING = 10;
    
    private Paint previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint controlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    
    private RectF previewRect = new RectF();
    private RectF controlRect = new RectF();
    
    private int color1 = 0xFF8AB4FF; // 默认主色
    private int color2 = 0xFFFF8AB3; // 默认副色
    private float gradientAngle = 0f; // 渐变角度 0-360
    private float color1Position = 0f; // 颜色1位置 0-1
    private float color2Position = 1f; // 颜色2位置 0-1
    
    private boolean isDraggingColor1 = false;
    private boolean isDraggingColor2 = false;
    private boolean isDraggingAngle = false;
    
    private OnGradientChangeListener listener;
    
    public GradientPickerView(Context context) {
        super(context);
        init();
    }
    
    public GradientPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public GradientPickerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        indicatorPaint.setStyle(Paint.Style.FILL);
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
        previewRect.set(PADDING, PADDING, w - PADDING, PADDING + PREVIEW_HEIGHT);
        controlRect.set(PADDING, previewRect.bottom + PADDING, w - PADDING, previewRect.bottom + PADDING + CONTROL_HEIGHT);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 绘制渐变预览
        drawGradientPreview(canvas);
        
        // 绘制颜色控制条
        drawColorControl(canvas);
        
        // 绘制角度控制
        drawAngleControl(canvas);
    }
    
    private void drawGradientPreview(Canvas canvas) {
        // 计算渐变方向
        float radians = (float) Math.toRadians(gradientAngle);
        float startX = previewRect.centerX() - (float) Math.cos(radians) * previewRect.width() / 2;
        float startY = previewRect.centerY() - (float) Math.sin(radians) * previewRect.height() / 2;
        float endX = previewRect.centerX() + (float) Math.cos(radians) * previewRect.width() / 2;
        float endY = previewRect.centerY() + (float) Math.sin(radians) * previewRect.height() / 2;
        
        // 创建渐变
        LinearGradient gradient = new LinearGradient(
                startX, startY, endX, endY,
                color1, color2, Shader.TileMode.CLAMP);
        previewPaint.setShader(gradient);
        canvas.drawRect(previewRect, previewPaint);
        canvas.drawRect(previewRect, borderPaint);
    }
    
    private void drawColorControl(Canvas canvas) {
        // 绘制控制条背景
        LinearGradient controlGradient = new LinearGradient(
                controlRect.left, controlRect.top, controlRect.right, controlRect.top,
                color1, color2, Shader.TileMode.CLAMP);
        controlPaint.setShader(controlGradient);
        canvas.drawRect(controlRect, controlPaint);
        canvas.drawRect(controlRect, borderPaint);
        
        // 绘制颜色1指示器
        float x1 = controlRect.left + color1Position * controlRect.width();
        drawColorIndicator(canvas, x1, controlRect.centerY(), color1);
        
        // 绘制颜色2指示器
        float x2 = controlRect.left + color2Position * controlRect.width();
        drawColorIndicator(canvas, x2, controlRect.centerY(), color2);
    }
    
    private void drawColorIndicator(Canvas canvas, float x, float y, int color) {
        // 绘制外圈
        indicatorPaint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, 12, indicatorPaint);
        
        // 绘制内圈（实际颜色）
        indicatorPaint.setColor(color);
        canvas.drawCircle(x, y, 8, indicatorPaint);
    }
    
    private void drawAngleControl(Canvas canvas) {
        // 简单的角度控制 - 可以后续扩展
        // 这里先绘制一个角度指示器
        float centerX = getWidth() / 2f;
        float centerY = controlRect.bottom + 30;
        
        // 绘制圆形角度选择器
        indicatorPaint.setColor(0x88FFFFFF);
        canvas.drawCircle(centerX, centerY, 20, indicatorPaint);
        
        // 绘制角度线
        float radians = (float) Math.toRadians(gradientAngle);
        float endX = centerX + (float) Math.cos(radians) * 15;
        float endY = centerY + (float) Math.sin(radians) * 15;
        
        indicatorPaint.setColor(Color.WHITE);
        indicatorPaint.setStrokeWidth(3f);
        canvas.drawLine(centerX, centerY, endX, endY, indicatorPaint);
        indicatorPaint.setStrokeWidth(1f);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // 检查是否点击了颜色1指示器
                float x1 = controlRect.left + color1Position * controlRect.width();
                if (Math.abs(x - x1) < 20 && Math.abs(y - controlRect.centerY()) < 20) {
                    isDraggingColor1 = true;
                    return true;
                }
                
                // 检查是否点击了颜色2指示器
                float x2 = controlRect.left + color2Position * controlRect.width();
                if (Math.abs(x - x2) < 20 && Math.abs(y - controlRect.centerY()) < 20) {
                    isDraggingColor2 = true;
                    return true;
                }
                
                // 检查是否点击了角度控制
                float centerX = getWidth() / 2f;
                float centerY = controlRect.bottom + 30;
                if (Math.abs(x - centerX) < 20 && Math.abs(y - centerY) < 20) {
                    isDraggingAngle = true;
                    return true;
                }
                break;
                
            case MotionEvent.ACTION_MOVE:
                if (isDraggingColor1) {
                    color1Position = (x - controlRect.left) / controlRect.width();
                    color1Position = Math.max(0, Math.min(1, color1Position));
                    invalidate();
                    notifyGradientChanged();
                    return true;
                } else if (isDraggingColor2) {
                    color2Position = (x - controlRect.left) / controlRect.width();
                    color2Position = Math.max(0, Math.min(1, color2Position));
                    invalidate();
                    notifyGradientChanged();
                    return true;
                } else if (isDraggingAngle) {
                    float centerX2 = getWidth() / 2f;
                    float centerY2 = controlRect.bottom + 30;
                    float dx = x - centerX2;
                    float dy = y - centerY2;
                    gradientAngle = (float) Math.toDegrees(Math.atan2(dy, dx));
                    if (gradientAngle < 0) gradientAngle += 360;
                    invalidate();
                    notifyGradientChanged();
                    return true;
                }
                break;
                
            case MotionEvent.ACTION_UP:
                isDraggingColor1 = false;
                isDraggingColor2 = false;
                isDraggingAngle = false;
                break;
        }
        
        return super.onTouchEvent(event);
    }
    
    private void notifyGradientChanged() {
        if (listener != null) {
            listener.onGradientChanged(color1, color2, gradientAngle, color1Position, color2Position);
        }
    }
    
    public int getColor1() { return color1; }
    public int getColor2() { return color2; }
    public float getGradientAngle() { return gradientAngle; }
    public float getColor1Position() { return color1Position; }
    public float getColor2Position() { return color2Position; }
    
    public void setColors(int color1, int color2) {
        this.color1 = color1;
        this.color2 = color2;
        invalidate();
    }
    
    public void setGradientAngle(float angle) {
        this.gradientAngle = angle;
        invalidate();
    }
    
    public void setOnGradientChangeListener(OnGradientChangeListener listener) {
        this.listener = listener;
    }
    
    public interface OnGradientChangeListener {
        void onGradientChanged(int color1, int color2, float angle, float position1, float position2);
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = 400;
        int desiredHeight = PREVIEW_HEIGHT + CONTROL_HEIGHT + 60 + PADDING * 3;
        
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