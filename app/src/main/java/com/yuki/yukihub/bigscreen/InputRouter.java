package com.yuki.yukihub.bigscreen;

import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * 输入抽象层（对应 bigscreen_spec.md §4.1 / §4.2）。
 *
 * <p>把「方向键 / ABXY / 肩键·扳机 / 摇杆 / 键盘兜底」统一翻译成意图枚举，
 * 上层 UI 只认意图，不认具体按键 —— 这样换手柄、加自定义映射都不用改业务代码。
 *
 * <p><b>接入方式（必须用 dispatchKeyEvent，不能只写 onKeyDown，否则 RecyclerView 会先吃掉方向键）</b>：
 * <pre>
 *   &#64;Override public boolean dispatchKeyEvent(KeyEvent e) {
 *       if (inputRouter.handleKeyEvent(e)) return true;
 *       return super.dispatchKeyEvent(e);
 *   }
 *   &#64;Override public boolean onGenericMotionEvent(MotionEvent e) {
 *       if (inputRouter.handleGenericMotion(e)) return true;
 *       return super.onGenericMotionEvent(e);
 *   }
 * </pre>
 *
 * <p>长按连发：首次 400ms 后开始、之后每 80ms 一次（spec §4.3）；
 * 摇杆死区 0.5，超过后进入同一节奏，避免摇杆微抖导致焦点乱跳。
 */
public class InputRouter {

    /** 意图枚举（spec §4.1） */
    public enum Intent {
        UP, DOWN, LEFT, RIGHT,
        CONFIRM,    // Ⓐ 启动 / 确认
        BACK,       // Ⓑ 返回
        FAVORITE,   // Ⓧ 收藏
        DETAILS,    // Ⓨ 详情
        PAGE_L,     // LB / L1·L2
        PAGE_R,     // RB / R1·R2
        MENU,       // ☰ START
        NONE
    }

    public interface Listener {
        /** 收到一个意图（连发时会重复回调同一个意图） */
        void onIntent(Intent intent);

        /** 手柄 / 外接输入设备插拔状态变化 */
        void onGamepadStateChanged(boolean connected);
    }

    /** 长按连发节奏（spec §4.3） */
    private static final long REPEAT_DELAY_MS = 400L;
    private static final long REPEAT_INTERVAL_MS = 80L;
    /**
     * 摇杆连发节奏（M14）：摇杆是连续量，用方向键的 80ms 会让列表每秒滚 12 格 ——
     * 焦点动画（140ms）根本跟不上，观感就是"卡顿不丝滑"。摇杆单独放慢到 150ms。
     */
    private static final long STICK_INTERVAL_MS = 150L;
    /** 摇杆死区 */
    private static final float STICK_DEADZONE = 0.5f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private Runnable repeatTask;
    private Intent repeatingIntent = Intent.NONE;
    private boolean gamepadConnected = false;
    private long lastStickEmit = 0L;

    public InputRouter(Listener listener) { this.listener = listener; }

    public boolean isGamepadConnected() { return gamepadConnected; }

    // ============ 按键 ============
    /**
     * 处理按键事件。
     *
     * @return true 表示已消费（调用方不要再往下传）
     */
    public boolean handleKeyEvent(KeyEvent event) {
        final Intent intent = mapKey(event.getKeyCode());
        if (intent == Intent.NONE) { return false; }

        final int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                emit(intent);
                if (isDirection(intent)) { startRepeat(intent, REPEAT_INTERVAL_MS); }
            } else if (isDirection(intent)) {
                // 系统连发不再二次发射，统一走自己的 80ms 节奏，保证手感一致
                return true;
            }
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            if (isDirection(intent)) { stopRepeat(); }
            return true;
        }
        return false;
    }

    // ============ 摇杆 / 十字帽 ============
    /**
     * 处理摇杆事件（在 onGenericMotionEvent 里转发）。
     *
     * @return true 表示已消费
     */
    public boolean handleGenericMotion(MotionEvent event) {
        final int source = event.getSource();
        final boolean isStick = (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        final boolean isDpad = (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
        if (!isStick && !isDpad) { return false; }

        float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (x == 0f && y == 0f) {
            // 部分手柄把十字帽报在 AXIS_X / AXIS_Y
            x = event.getAxisValue(MotionEvent.AXIS_X);
            y = event.getAxisValue(MotionEvent.AXIS_Y);
        }

        final Intent intent = stickIntent(x, y);
        if (intent == Intent.NONE) {
            stopRepeat();
            return false;
        }

        final long now = System.currentTimeMillis();
        if (repeatingIntent != intent) {
            // 方向变了：立刻响应一次，并重置连发
            emit(intent);
            startRepeat(intent, STICK_INTERVAL_MS);
        } else if (now - lastStickEmit >= STICK_INTERVAL_MS) {
            emit(intent);
        }
        lastStickEmit = now;
        return true;
    }

    private Intent stickIntent(float x, float y) {
        if (Math.abs(x) < STICK_DEADZONE && Math.abs(y) < STICK_DEADZONE) { return Intent.NONE; }
        if (Math.abs(x) >= Math.abs(y)) { return x > 0 ? Intent.RIGHT : Intent.LEFT; }
        return y > 0 ? Intent.DOWN : Intent.UP;
    }

    // ============ 手柄连接状态 ============
    /** 扫描当前已连接的输入设备（onCreate / onResume 调用） */
    public void refreshGamepadState() {
        boolean found = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) { continue; }
            final int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                found = true;
                break;
            }
        }
        setGamepadConnected(found);
    }

    /** 由 Activity 从 InputManager.InputDeviceListener 转发 */
    public void onDeviceAdded(int deviceId) { refreshGamepadState(); }

    /** 由 Activity 从 InputManager.InputDeviceListener 转发 */
    public void onDeviceRemoved(int deviceId) { refreshGamepadState(); }

    private void setGamepadConnected(boolean connected) {
        if (gamepadConnected == connected) { return; }
        gamepadConnected = connected;
        if (listener != null) { listener.onGamepadStateChanged(connected); }
    }

    // ============ 生命周期 ============
    /** 页面离开时调用，避免 Handler 持有引用导致泄漏 */
    public void release() { stopRepeat(); }

    // ============ 内部 ============
    private void emit(Intent intent) {
        if (isDirection(intent)) { repeatingIntent = intent; }
        if (listener != null) { listener.onIntent(intent); }
    }

    private void startRepeat(final Intent intent, final long interval) {
        stopRepeat();
        repeatingIntent = intent;
        repeatTask = new Runnable() {
            @Override public void run() {
                if (listener != null) { listener.onIntent(intent); }
                handler.postDelayed(this, interval);
            }
        };
        handler.postDelayed(repeatTask, REPEAT_DELAY_MS);
    }

    private void stopRepeat() {
        if (repeatTask != null) {
            handler.removeCallbacks(repeatTask);
            repeatTask = null;
        }
        repeatingIntent = Intent.NONE;
    }

    private static boolean isDirection(Intent intent) {
        return intent == Intent.UP || intent == Intent.DOWN
                || intent == Intent.LEFT || intent == Intent.RIGHT;
    }

    /**
     * 按键 → 意图。
     * 前半段是手柄按键；后半段是键盘兜底（没有手柄时也能用蓝牙键盘 / 模拟器全流程调试）。
     */
    private Intent mapKey(int keyCode) {
        switch (keyCode) {
            // —— 方向 ——
            case KeyEvent.KEYCODE_DPAD_UP:      return Intent.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:    return Intent.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:    return Intent.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:   return Intent.RIGHT;

            // —— ABXY ——
            case KeyEvent.KEYCODE_BUTTON_A:     return Intent.CONFIRM;
            case KeyEvent.KEYCODE_BUTTON_B:     return Intent.BACK;
            case KeyEvent.KEYCODE_BUTTON_X:     return Intent.FAVORITE;
            case KeyEvent.KEYCODE_BUTTON_Y:     return Intent.DETAILS;

            // —— 肩键 / 扳机 ——
            case KeyEvent.KEYCODE_BUTTON_L1:    return Intent.PAGE_L;
            case KeyEvent.KEYCODE_BUTTON_L2:    return Intent.PAGE_L;
            case KeyEvent.KEYCODE_BUTTON_R1:    return Intent.PAGE_R;
            case KeyEvent.KEYCODE_BUTTON_R2:    return Intent.PAGE_R;

            // —— 菜单键 ——
            case KeyEvent.KEYCODE_BUTTON_START:  return Intent.MENU;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_MODE:   return Intent.BACK;

            // —— 键盘兜底（无手柄调试用）——
            case KeyEvent.KEYCODE_W:            return Intent.UP;
            case KeyEvent.KEYCODE_S:            return Intent.DOWN;
            case KeyEvent.KEYCODE_A:            return Intent.LEFT;
            case KeyEvent.KEYCODE_D:            return Intent.RIGHT;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_CENTER:  return Intent.CONFIRM;
            case KeyEvent.KEYCODE_ESCAPE:       return Intent.BACK;
            case KeyEvent.KEYCODE_X:            return Intent.FAVORITE;
            case KeyEvent.KEYCODE_Y:            return Intent.DETAILS;
            case KeyEvent.KEYCODE_Q:            return Intent.PAGE_L;
            case KeyEvent.KEYCODE_E:            return Intent.PAGE_R;
            case KeyEvent.KEYCODE_TAB:          return Intent.MENU;
            default:                            return Intent.NONE;
        }
    }
}