package com.yuki.yukihub.bigscreen;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.view.HapticFeedbackConstants;
import android.view.View;

import com.yuki.yukihub.R;

/**
 * 大屏界面反馈（音效 + 触感）。
 *
 * <p>**复用项目既有音效资源**（`res/raw/ui_click·ui_confirm·ui_switch`，Kenney UI Audio，CC0）——
 * 不新增素材。若之后要换成原型那套音效，把文件放进 `res/raw` 再改这里的映射即可。
 *
 * <p>映射：焦点移动 → ui_click ｜ 确认 / 启动 → ui_confirm ｜ 打开菜单 / 详情 / 返回 → ui_switch
 *
 * <p>防抖：焦点音效最小间隔 {@link #FOCUS_MIN_INTERVAL_MS}，避免快速连划时"哒哒哒"糊成一片。
 */
public class BigScreenSound {

    public enum Sfx { FOCUS, CONFIRM, OPEN }

    /** 焦点音效最小间隔（毫秒） */
    private static final long FOCUS_MIN_INTERVAL_MS = 55L;

    private SoundPool pool;
    private int clickId = 0;
    private int confirmId = 0;
    private int switchId = 0;

    /**
     * 全局实例（M12）：音效不该只挂在 Activity 的按键通路上 ——
     * 触摸点击、侧栏点击、面板/设置条目、详情层按钮都要能发声。
     * 用静态入口，避免给每个组件都接一条回调链。
     */
    private static BigScreenSound sInstance;

    public static BigScreenSound get() { return sInstance; }

    /** 静态快捷入口（实例不存在时静默） */
    public static void tick() { BigScreenSound s = sInstance; if (s != null) { s.play(Sfx.FOCUS); } }
    public static void confirm() { BigScreenSound s = sInstance; if (s != null) { s.play(Sfx.CONFIRM); } }
    public static void open() { BigScreenSound s = sInstance; if (s != null) { s.play(Sfx.OPEN); } }

    // ================= 触感（M22）=================
    /** 触摸点击的轻震（与游戏库 {@code GameAdapter} 同一档 {@code VIRTUAL_KEY}） */
    public static void hapticKey(View v) { haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); }

    /** 长按 / 重按的震动（与游戏库长按同一档 {@code LONG_PRESS}） */
    public static void hapticLong(View v) { haptic(v, HapticFeedbackConstants.LONG_PRESS); }

    /**
     * 在指定 View 上触发触感。
     *
     * <p>用 {@code performHapticFeedback()} 而不是 {@code Vibrator}：不需要 VIBRATE 权限
     * （Manifest 里本来也没声明），而且会自动尊重系统的"触摸时振动"开关与强度设置。
     *
     * <p>**只给触摸用**——手柄的按键不给手机发震：手柄用户物理上已经在按了，
     * 多一次机身震动只是噪音（真正的手柄"马达震动"是另一套机制，要走
     * {@code InputDevice.getVibrator()}，不在本次范围）。
     *
     * <p>档位只用 {@code VIRTUAL_KEY} / {@code LONG_PRESS}：这两个从 API 3 就有，
     * 而 {@code CONFIRM} 等新常量在旧系统上没有对应实现（本项目 minSdk 26）。
     */
    private static void haptic(View v, int type) {
        if (v == null) { return; }
        try {
            v.performHapticFeedback(type);
        } catch (Throwable ignored) { }
    }

    private boolean enabled = true;
    private float leftVolume = 0.65f;
    private float rightVolume = 0.65f;
    private long lastFocusAt = 0L;

    public BigScreenSound(Context context) {
        try {
            pool = new SoundPool(4, AudioManager.STREAM_MUSIC, 0);
            clickId = pool.load(context, R.raw.ui_click, 1);
            confirmId = pool.load(context, R.raw.ui_confirm, 1);
            switchId = pool.load(context, R.raw.ui_switch, 1);
        } catch (Throwable ignored) { }
        sInstance = this;
    }

    /** 总开关（对应设置项 bigscreen_sound_enabled） */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** 音量 0~100（对应设置项 bigscreen_sound_volume） */
    public void setVolume(int volume0to100) {
        float v = Math.max(0, Math.min(100, volume0to100)) / 100f;
        // 界面音效不该太响：上限压到 0.8
        float scaled = Math.min(0.8f, v);
        this.leftVolume = scaled;
        this.rightVolume = scaled;
    }

    public void play(Sfx sfx) {
        if (!enabled || pool == null) { return; }
        try {
            if (sfx == Sfx.FOCUS) {
                long now = System.currentTimeMillis();
                if (now - lastFocusAt < FOCUS_MIN_INTERVAL_MS) { return; }
                lastFocusAt = now;
                if (clickId != 0) { pool.play(clickId, leftVolume, rightVolume, 1, 0, 1.0f); }
            } else if (sfx == Sfx.CONFIRM) {
                if (confirmId != 0) { pool.play(confirmId, leftVolume, rightVolume, 1, 0, 1.0f); }
            } else {
                if (switchId != 0) { pool.play(switchId, leftVolume, rightVolume, 1, 0, 1.0f); }
            }
        } catch (Throwable ignored) { }
    }

    public void release() {
        if (sInstance == this) { sInstance = null; }
        if (pool == null) { return; }
        try { pool.release(); } catch (Throwable ignored) { }
        pool = null;
        clickId = confirmId = switchId = 0;
    }
}