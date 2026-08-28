package com.yuki.yukihub.ons;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

/**
 * 播过场视频时压制引擎音频。
 *
 * 为什么需要：上游 {@code ONScripter_sound.cpp} 的 Android 分支只调
 * {@code playVideoAndroid()} 就 return，没有像其他平台那样执行
 * {@code Mix_HookMusic(NULL,NULL)} + {@code stopSMPEG()}，
 * 播 op 时游戏 BGM 仍在响。这是引擎行为，不是集成层 bug。
 *
 * 试过但无效的方案：改 {@code SDLAudioManager.mAudioTrack} 音量。
 * 实测 0.7.7 的 SDL 走 AAudio 输出（日志有 {@code AAudioStreamBuilder_openStream}），
 * Java 侧那个 AudioTrack 字段是 null。
 *
 * 现在的做法是两层：
 * 1. {@code SDLActivity.nativePause()} —— SDL 标准暂停，会停掉音频回调。
 *    引擎在 {@code SDL_WINDOWEVENT_FOCUS_GAINED} 分支有无条件的
 *    {@code Mix_ResumeMusic()}，所以 resume 一定能恢复。
 * 2. 音频焦点 —— 兜底，万一 nativePause 在某些版本上不生效，
 *    系统 ducking 至少能压低音量。
 */
public final class OnsAudioFocus {

    private static final String TAG = "OnsAudioFocus";

    private static AudioManager manager;
    private static Object requestRef;          // API 26+ 的 AudioFocusRequest
    private static AudioManager.OnAudioFocusChangeListener legacyListener;

    private OnsAudioFocus() { }

    /** 播片开始：申请音频焦点。 */
    public static void acquire(Context ctx) {
        requestFocus(ctx);
    }

    /** 播片结束：释放音频焦点。 */
    public static void release() {
        abandonFocus();
    }
    /*
     * 试过但不能用的方案：SDLActivity.nativePause() / nativeResume()。
     * 它确实会停掉引擎音频，但同时触发窗口 relayout
     * （实测 2414x1080 → 2161x959），视频层随之拿到畸变尺寸
     * （setBuffersGeometry w=1278,h=959），缓冲区填不满就出现白边紫边。
     * 所以只保留音频焦点这一层。
     */
    private static void requestFocus(Context ctx) {
        try {
            if (manager == null) {
                manager = (AudioManager) ctx.getApplicationContext()
                        .getSystemService(Context.AUDIO_SERVICE);
            }
            if (manager == null) return;

            if (Build.VERSION.SDK_INT >= 26) {
                if (requestRef != null) return;   // 已持有
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build();
                AudioFocusRequest req = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs)
                        .setWillPauseWhenDucked(false)
                        .build();
                int result = manager.requestAudioFocus(req);
                requestRef = req;
                Log.i(TAG, "audio focus requested, result=" + result);
            } else {
                if (legacyListener != null) return;
                legacyListener = focusChange -> { };
                int result = manager.requestAudioFocus(legacyListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                Log.i(TAG, "audio focus requested (legacy), result=" + result);
            }
        } catch (Throwable t) {
            Log.w(TAG, "acquire failed", t);
        }
    }

    /** 释放焦点，引擎音频恢复正常音量。 */
    private static void abandonFocus() {
        try {
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                if (requestRef instanceof AudioFocusRequest) {
                    manager.abandonAudioFocusRequest((AudioFocusRequest) requestRef);
                    Log.i(TAG, "audio focus released");
                }
                requestRef = null;
            } else {
                if (legacyListener != null) {
                    manager.abandonAudioFocus(legacyListener);
                    legacyListener = null;
                    Log.i(TAG, "audio focus released (legacy)");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "release failed", t);
            requestRef = null;
            legacyListener = null;
        }
    }
}