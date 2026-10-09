package com.yuki.yukihub.fvp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * FVP 引擎的虚拟按键条（左侧竖排三键）。
 *
 * <p>布局（自左上到左下）：
 * <pre>
 *   ┌──────┐
 *   │ ESC  │  ← 打开游戏菜单（VK 0x1B）
 *   ├──────┤
 *   │ 历史 │  ← 历史记录（VK 0x26 ArrowUp，对应 PC 的"鼠标上滚轮"）
 *   ├──────┤
 *   │ Ctrl │  ← 快进（VK 0x11，长按连发）
 *   └──────┘
 * </pre>
 *
 * <p><b>位置策略</b>：FVP 在手机上是「保持宽高比居中」渲染的，左右两侧通常有黑边。
 * 本控件默认贴在**左侧黑边**里（宽 {@link #BAR_WIDTH_DP}dp），因此不会遮挡游戏内容。
 * 若用户开了「画面铺满全屏」（设置里的 stretch_fill），黑边消失，
 * 此时按键会浮在画面左侧 —— 由用户自行取舍（与改版的做法一致）。
 *
 * <p><b>为什么不用系统悬浮窗</b>：FvpActivity 是普通 Activity（SurfaceView 由系统合成），
 * 直接挂在 rootLayout 上即可，不需要「显示在其他应用上层」权限。
 *
 * <p><b>按键注入</b>：走 {@link NativeRfvp#keyEvent}（C 桥 → rfvp_android_key）。
 * 引擎侧的键码语义是 **Windows VK**：0x1B Escape / 0x11 Control / 0x25..0x28 方向键。
 */
public final class FvpVirtualKeys {

    private static final String TAG = "FvpVirtualKeys";

    // ---- Windows VK 码（与 rfvp app.rs::host_key_android 一致）----
    /** Escape：打开/关闭游戏菜单。 */
    private static final int VK_ESCAPE = 0x1B;
    /** Control：快进（按住）。 */
    private static final int VK_CONTROL = 0x11;
    /** ArrowUp：历史记录（PC 上鼠标上滚轮的行为）。 */
    private static final int VK_UP = 0x26;

    /** phase 语义（host_key_android）：0 = down，1 = up。 */
    private static final int PHASE_DOWN = 0;
    private static final int PHASE_UP = 1;

    /** 按钮宽度。 */
    private static final float BTN_WIDTH_DP = 62f;
    /** 按钮高度。 */
    private static final float BTN_HEIGHT_DP = 48f;
    /** 按钮文字大小（sp）。 */
    private static final float BTN_TEXT_SP = 15f;
    /** 按钮间距。 */
    private static final float BTN_GAP_DP = 40f;
    /** 按钮离屏幕左边缘的距离。 */
    private static final float EDGE_DP = 12f;

    /** 常态不透明度 40%（0x66/255）。 */
    private static final int BG_NORMAL = 0x66333333;
    /** 按下时不透明度 60%（0x99/255）。 */
    private static final int BG_PRESSED = 0x991976D2;

    /** Ctrl 长按连发的首次延迟（毫秒），模拟物理键盘的按键重复。 */
    private static final long CTRL_REPEAT_INITIAL_MS = 300L;
    /** Ctrl 长按连发的间隔（毫秒）。 */
    private static final long CTRL_REPEAT_INTERVAL_MS = 45L;

    private final Activity host;
    private final LinearLayout bar;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 引擎句柄提供者（懒取；0 = 未就绪）。 */
    private final HandleProvider handleProvider;

    private final TextView escButton;
    private final TextView historyButton;
    private final TextView ctrlButton;

    private boolean escDown;
    private boolean ctrlDown;

    /** 返回当前引擎句柄；0 表示未就绪。 */
    public interface HandleProvider {
        long handle();
    }

    private final Runnable ctrlRepeat = new Runnable() {
        @Override
        public void run() {
            if (!ctrlDown) return;
            long h = handleProvider.handle();
            if (h == 0L) return;
            // 连发用 down（引擎按帧读按住状态），不合成 up。
            NativeRfvp.keyEvent(h, VK_CONTROL, PHASE_DOWN);
            handler.postDelayed(this, CTRL_REPEAT_INTERVAL_MS);
        }
    };

    public FvpVirtualKeys(Activity host, HandleProvider handleProvider) {
        this.host = host;
        this.handleProvider = handleProvider;

        float d = host.getResources().getDisplayMetrics().density;
        bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.VERTICAL);
        // 容器全高时，三个按钮整体在左侧垂直居中。
        bar.setGravity(Gravity.CENTER_VERTICAL);

        escButton = makeButton("ESC");
        historyButton = makeButton("历史");
        ctrlButton = makeButton("Ctrl");

        int bw = Math.round(BTN_WIDTH_DP * d);
        int bh = Math.round(BTN_HEIGHT_DP * d);
        int edge = Math.round(EDGE_DP * d);
        int gap = Math.round(BTN_GAP_DP * d);
        bar.setPadding(edge, 0, 0, 0);

        // 三键紧挨着堆叠（间距统一），整体居中，不分散到顶/底。
        bar.addView(escButton, new LinearLayout.LayoutParams(bw, bh));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(bw, bh);
        hLp.topMargin = gap;
        bar.addView(historyButton, hLp);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(bw, bh);
        cLp.topMargin = gap;
        bar.addView(ctrlButton, cLp);

        installListeners();
    }

    /** 把按键条挂到 DecorView 的最上层（与虚拟鼠标容器同一层级，靠 addView 顺序压住它）。 */
    public void attach() {
        if (bar.getParent() != null) return;
        // 挂在 DecorView 上而不是 Activity 内容视图：虚拟鼠标容器也挂在 DecorView，
        // 挂在内容视图会被它整个盖住（点不到）。
        ViewGroup decor = null;
        try {
            decor = (ViewGroup) host.getWindow().getDecorView();
        } catch (Throwable ignored) { }
        // 兵底：DecorView 拿不到时用内容视图（虽然会被鼠标容器盖住，但不至于崩）
        View content = null;
        try {
            content = host.findViewById(android.R.id.content);
        } catch (Throwable ignored) { }
        ViewGroup parent = decor != null ? decor
                : (content instanceof ViewGroup ? (ViewGroup) content : null);
        if (parent == null) {
            android.util.Log.w(TAG, "no parent for virtual keys");
            return;
        }
        // 全高、宽度只包住按钮（左侧一条），其余区域触摸穿透。
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        bar.setElevation(2000f);
        parent.addView(bar, lp);
        bar.bringToFront();
        android.util.Log.i(TAG, "virtual keys attached (ESC / 历史 / Ctrl)");
    }

    public void detach() {
        releaseAll();
        if (bar.getParent() instanceof ViewGroup) {
            ((ViewGroup) bar.getParent()).removeView(bar);
        }
    }

    /** 宿主 Activity 暂停/销毁时释放所有按下状态，避免按键卡住。 */
    public void releaseAll() {
        handler.removeCallbacks(ctrlRepeat);
        long h = handleProvider.handle();
        if (escDown && h != 0L) {
            NativeRfvp.keyEvent(h, VK_ESCAPE, PHASE_UP);
        }
        if (ctrlDown && h != 0L) {
            NativeRfvp.keyEvent(h, VK_CONTROL, PHASE_UP);
        }
        escDown = false;
        ctrlDown = false;
        setPressedState(escButton, false);
        setPressedState(ctrlButton, false);
    }

    // ---------------------------------------------------------------- 内部

    private TextView makeButton(String text) {
        Context ctx = host;
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(BTN_TEXT_SP);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setFocusable(false);
        setPressedState(v, false);
        return v;
    }

    private void setPressedState(TextView v, boolean pressed) {
        float d = host.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(12f * d);
        bg.setColor(pressed ? BG_PRESSED : BG_NORMAL);
        bg.setStroke(Math.max(1, Math.round(d)), pressed ? 0xCCFFFFFF : 0x66FFFFFF);
        v.setBackground(bg);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void installListeners() {
        // ESC：单击发一次 down+up（引擎在下一帧读到边沿即开菜单）
        escButton.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (escDown) return true;
                    long h = handleProvider.handle();
                    if (h == 0L) return true;
                    escDown = true;
                    setPressedState(escButton, true);
                    NativeRfvp.keyEvent(h, VK_ESCAPE, PHASE_DOWN);
                    // 抬起放在 60ms 后：引擎按帧读边沿，太短会被掉帧漏掉。
                    handler.postDelayed(() -> {
                        if (!escDown) return;
                        escDown = false;
                        setPressedState(escButton, false);
                        long h2 = handleProvider.handle();
                        if (h2 != 0L) NativeRfvp.keyEvent(h2, VK_ESCAPE, PHASE_UP);
                    }, 60L);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
        });

        // 历史：单击发一次 ArrowUp（PC 上"鼠标上滚轮 = 打开历史记录"）
        historyButton.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    long h = handleProvider.handle();
                    if (h == 0L) return true;
                    setPressedState(historyButton, true);
                    NativeRfvp.keyEvent(h, VK_UP, PHASE_DOWN);
                    handler.postDelayed(() -> {
                        setPressedState(historyButton, false);
                        long h2 = handleProvider.handle();
                        if (h2 != 0L) NativeRfvp.keyEvent(h2, VK_UP, PHASE_UP);
                    }, 60L);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
        });

        // Ctrl：按住连发（快进）
        ctrlButton.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (ctrlDown) return true;
                    long h = handleProvider.handle();
                    if (h == 0L) return true;
                    ctrlDown = true;
                    setPressedState(ctrlButton, true);
                    NativeRfvp.keyEvent(h, VK_CONTROL, PHASE_DOWN);
                    handler.postDelayed(ctrlRepeat, CTRL_REPEAT_INITIAL_MS);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    handler.removeCallbacks(ctrlRepeat);
                    if (ctrlDown) {
                        ctrlDown = false;
                        setPressedState(ctrlButton, false);
                        long h2 = handleProvider.handle();
                        if (h2 != 0L) NativeRfvp.keyEvent(h2, VK_CONTROL, PHASE_UP);
                    }
                    return true;
                }
                default:
                    return true;
            }
        });
    }
}
