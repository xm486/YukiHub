package com.yuki.yukihub.translate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 全屏区域选择 View。
 *
 * 参考 MoeTranslate CropView 的八方向缩放和区域移动逻辑，转换为 Java。
 * 坐标均为此 View 内坐标；截图时通过 getLocationOnScreen() 转成屏幕偏移。
 */
public class TranslateCropView extends View {
    public interface Listener {
        void onConfirmed(RectF rect, Point screenOffset);
        void onCancelled();
    }

    private static final float MIN_SIZE_PX = 50f;
    private static final float HANDLE_RADIUS_SQ = 42f * 42f;
    private static final int NONE = -1;
    private static final int MOVE = 8;

    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF downRect = new RectF();
    private final Point downPoint = new Point();
    private final Point lastPoint = new Point();
    private final Listener listener;
    private int action = NONE;

    public TranslateCropView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setFocusable(true);
        shadePaint.setColor(0x99000000);
        shadePaint.setStyle(Paint.Style.FILL);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        handlePaint.setColor(0xFF6750A4);
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setStrokeWidth(6f);
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setRect(RectF value) {
        if (value == null) {
            post(() -> setDefaultRect());
            return;
        }
        rect.set(value);
        clampRect();
        invalidate();
    }

    public RectF getRect() {
        return new RectF(rect);
    }

    public Point getScreenOffset() {
        int[] location = new int[2];
        getLocationOnScreen(location);
        return new Point(location[0], location[1]);
    }

    public void cancel() {
        if (listener != null) listener.onCancelled();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (rect.isEmpty()) setDefaultRect();
        else clampRect();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (rect.isEmpty()) return;
        canvas.drawRect(0, 0, getWidth(), rect.top, shadePaint);
        canvas.drawRect(0, rect.top, rect.left, rect.bottom, shadePaint);
        canvas.drawRect(rect.right, rect.top, getWidth(), rect.bottom, shadePaint);
        canvas.drawRect(0, rect.bottom, getWidth(), getHeight(), shadePaint);
        canvas.drawRect(rect, borderPaint);
        drawHandles(canvas);
    }

    private void drawHandles(Canvas canvas) {
        float midX = (rect.left + rect.right) / 2f;
        float midY = (rect.top + rect.bottom) / 2f;
        canvas.drawLine(rect.left, rect.top, rect.left + 32, rect.top, handlePaint);
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + 32, handlePaint);
        canvas.drawLine(midX - 20, rect.top, midX + 20, rect.top, handlePaint);
        canvas.drawLine(rect.right - 32, rect.top, rect.right, rect.top, handlePaint);
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + 32, handlePaint);
        canvas.drawLine(rect.left, midY - 20, rect.left, midY + 20, handlePaint);
        canvas.drawLine(rect.right, midY - 20, rect.right, midY + 20, handlePaint);
        canvas.drawLine(rect.left, rect.bottom - 32, rect.left, rect.bottom, handlePaint);
        canvas.drawLine(rect.left, rect.bottom, rect.left + 32, rect.bottom, handlePaint);
        canvas.drawLine(midX - 20, rect.bottom, midX + 20, rect.bottom, handlePaint);
        canvas.drawLine(rect.right - 32, rect.bottom, rect.right, rect.bottom, handlePaint);
        canvas.drawLine(rect.right, rect.bottom - 32, rect.right, rect.bottom, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downPoint.set(Math.round(x), Math.round(y));
                lastPoint.set(downPoint.x, downPoint.y);
                downRect.set(rect);
                action = hitTest(x, y);
                if (action == NONE) action = MOVE;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = x - lastPoint.x;
                float dy = y - lastPoint.y;
                updateRect(x, y, dx, dy);
                lastPoint.set(Math.round(x), Math.round(y));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                clampRect();
                invalidate();
                action = NONE;
                // 区域 View 只负责调整选区；确认由悬浮球单击完成，保持与萌译一致。
                return true;
            case MotionEvent.ACTION_CANCEL:
                action = NONE;
                return true;
            default:
                return true;
        }
    }

    private void updateRect(float x, float y, float dx, float dy) {
        switch (action) {
            case 0:
                rect.left = Math.min(x, downRect.right - MIN_SIZE_PX);
                rect.top = Math.min(y, downRect.bottom - MIN_SIZE_PX);
                break;
            case 1:
                rect.top = Math.min(y, downRect.bottom - MIN_SIZE_PX);
                break;
            case 2:
                rect.right = Math.max(x, downRect.left + MIN_SIZE_PX);
                rect.top = Math.min(y, downRect.bottom - MIN_SIZE_PX);
                break;
            case 3:
                rect.left = Math.min(x, downRect.right - MIN_SIZE_PX);
                break;
            case 4:
                rect.right = Math.max(x, downRect.left + MIN_SIZE_PX);
                break;
            case 5:
                rect.left = Math.min(x, downRect.right - MIN_SIZE_PX);
                rect.bottom = Math.max(y, downRect.top + MIN_SIZE_PX);
                break;
            case 6:
                rect.bottom = Math.max(y, downRect.top + MIN_SIZE_PX);
                break;
            case 7:
                rect.right = Math.max(x, downRect.left + MIN_SIZE_PX);
                rect.bottom = Math.max(y, downRect.top + MIN_SIZE_PX);
                break;
            case MOVE:
                rect.offset(dx, dy);
                break;
            default:
                break;
        }
        clampRect();
    }

    private int hitTest(float x, float y) {
        float midX = (rect.left + rect.right) / 2f;
        float midY = (rect.top + rect.bottom) / 2f;
        if (near(x, y, rect.left, rect.top)) return 0;
        if (near(x, y, midX, rect.top)) return 1;
        if (near(x, y, rect.right, rect.top)) return 2;
        if (near(x, y, rect.left, midY)) return 3;
        if (near(x, y, rect.right, midY)) return 4;
        if (near(x, y, rect.left, rect.bottom)) return 5;
        if (near(x, y, midX, rect.bottom)) return 6;
        if (near(x, y, rect.right, rect.bottom)) return 7;
        return rect.contains(x, y) ? MOVE : NONE;
    }

    private boolean near(float x, float y, float cx, float cy) {
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy <= HANDLE_RADIUS_SQ;
    }

    private void setDefaultRect() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float marginX = getWidth() * 0.12f;
        float marginY = getHeight() * 0.25f;
        rect.set(marginX, marginY, getWidth() - marginX, getHeight() - marginY);
        clampRect();
        invalidate();
    }

    private void clampRect() {
        if (getWidth() <= 0 || getHeight() <= 0 || rect.isEmpty()) return;
        float maxLeft = Math.max(0f, getWidth() - MIN_SIZE_PX);
        float maxTop = Math.max(0f, getHeight() - MIN_SIZE_PX);
        if (rect.left < 0) rect.offset(-rect.left, 0);
        if (rect.top < 0) rect.offset(0, -rect.top);
        if (rect.right > getWidth()) rect.offset(getWidth() - rect.right, 0);
        if (rect.bottom > getHeight()) rect.offset(0, getHeight() - rect.bottom);
        if (rect.width() < MIN_SIZE_PX) rect.right = Math.min(getWidth(), rect.left + MIN_SIZE_PX);
        if (rect.height() < MIN_SIZE_PX) rect.bottom = Math.min(getHeight(), rect.top + MIN_SIZE_PX);
        rect.left = Math.max(0, Math.min(rect.left, maxLeft));
        rect.top = Math.max(0, Math.min(rect.top, maxTop));
        rect.right = Math.min(getWidth(), Math.max(rect.right, rect.left + MIN_SIZE_PX));
        rect.bottom = Math.min(getHeight(), Math.max(rect.bottom, rect.top + MIN_SIZE_PX));
    }
}