package com.yuki.yukihub.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.Spinner;

/**
 * 「滑动安全」的下拉选择块（Spinner）。
 *
 * <p>问题：系统 Spinner 在下拉模式（MODE_DROPDOWN）下会挂一个 {@code ForwardingListener}，
 * 它为了支持「按住拖动直接选项」这套手势，会在手指按下后做两件事：
 * <ul>
 *   <li>约 {@code ViewConfiguration.getTapTimeout()}（≈100ms）后
 *       {@code requestDisallowInterceptTouchEvent(true)}——把父容器（ScrollView）锁死；</li>
 *   <li>约 (tapTimeout + longPressTimeout) / 2（≈300ms）后直接弹出下拉列表。</li>
 * </ul>
 * 只有「手指移出控件自身边界 + touchSlop」才会取消这两个回调。而设置面板里的选择块只有
 * 44dp 高，手指上下滑动时经常还没移出边界、或者刚按下就停顿一下，于是滑动被判定成点击 /
 * 长按：滚动被硬控住，还莫名其妙弹出选择列表。
 *
 * <p>做法：彻底绕开那套转发逻辑，自己接管触摸——
 * 只有「按下后位移没超过 touchSlop 就松手」才算点击；一旦检测到拖动就立刻放手，
 * 让父容器（设置面板的 ScrollView）接管这次手势去滚动，自己什么都不做。
 *
 * <p>代价是不再支持系统 Spinner 的「按住拖动直接选项」手势——那正是误触的来源，
 * 对触屏设置面板来说属于净收益。
 *
 * <p>用法与普通 Spinner 完全一致：{@code new TapSafeSpinner(context)}，或在 XML 里写全类名。
 */
public class TapSafeSpinner extends Spinner {

    /** 判定「这是滑动而不是点击」的位移阈值（与父容器 ScrollView 用的同一套 dp 换算）。 */
    private final int touchSlop;

    private float downX;
    private float downY;
    /** 本次手势是否已经越过阈值、进入「拖动（交给父容器滚动）」状态。 */
    private boolean dragged;

    public TapSafeSpinner(Context context) {
        this(context, null);
    }

    public TapSafeSpinner(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.spinnerStyle);
    }

    public TapSafeSpinner(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public TapSafeSpinner(Context context, AttributeSet attrs, int defStyleAttr, int mode) {
        super(context, attrs, defStyleAttr, mode);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null || !isEnabled()) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragged = false;
                setPressed(true);
                // 自己吃掉 DOWN：这样 Spinner 内部的 ForwardingListener 完全不会被触发，
                // 也就不会再有「100ms 锁父容器 / 300ms 弹下拉」的副作用。
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!dragged) {
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    if (dx > touchSlop || dy > touchSlop) {
                        dragged = true;
                        setPressed(false);
                        // 明确清掉「禁止父容器拦截」的标记，确保 ScrollView 能接管这次滑动。
                        ViewParent parent = getParent();
                        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
                // 拖动中不做任何事：真正滚不滚交给父容器判断。
                return true;

            case MotionEvent.ACTION_UP:
                setPressed(false);
                // 只有「没拖过」且「松手时仍落在控件范围内」才算点击。
                // 后者兜住「慢慢滑出边界、位移还没到 slop」的情况。
                if (!dragged && inViewBounds(event.getX(), event.getY())) {
                    performClick(); // 真正的「点一下」才弹下拉
                }
                dragged = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                dragged = false;
                return true;

            default:
                return true;
        }
    }

    /**
     * 松手点是否仍落在控件自身范围内（带一点余量，避免边界像素抖动）。
     *
     * <p>不用 {@code View.pointInView()}：那是 {@code @hide} 的框架内部方法，
     * 编译公开 SDK 时找不到符号。
     */
    private boolean inViewBounds(float x, float y) {
        return x >= -touchSlop && y >= -touchSlop
                && x <= getWidth() + touchSlop && y <= getHeight() + touchSlop;
    }
}
