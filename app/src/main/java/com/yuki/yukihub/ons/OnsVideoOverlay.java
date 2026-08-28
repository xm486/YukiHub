package com.yuki.yukihub.ons;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;

/**
 * ONS 游戏视频的「窗口内覆盖播放」控制器。
 *
 * 为什么不用独立 Activity：
 * ONScripter 声明为 launchMode="singleInstance" 且有独立 taskAffinity，
 * 启动另一个 Activity 播视频会导致 task 前后台切换，实测后果是
 * SDL 收到 onStop()、播完 finish 后回到的是 MainActivity 而不是游戏本身。
 * 而 native 侧 playVideoAndroid 是 fire-and-forget（ONScripter_sound.cpp:379
 * 调用后立即返回继续执行脚本），并不需要独立页面来「阻塞等待」。
 *
 * 所以正确做法是把播放器直接 addContentView 盖在 ONScripter 自己的窗口上：
 * 零 Activity 切换、SDL 生命周期不受干扰、播完移除视图即可回到游戏画面。
 *
 * 线程约定：所有公开方法都必须在主线程调用，内部回调也会切回主线程。
 */
public final class OnsVideoOverlay {

    private static final String TAG = "OnsVideoOverlay";

    private final Activity host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout container;
    private OnsIjkVideoView videoView;
    private TextView skipHint;
    private ParcelFileDescriptor pfd;
    private boolean skippable = true;
    /** 防止重复清理。 */
    private boolean dismissed;

    public OnsVideoOverlay(Activity host) {
        this.host = host;
    }

    /** 当前是否正在播放，供宿主决定按键/触摸事件是否该交给视频层。 */
    public boolean isPlaying() {
        return container != null && !dismissed;
    }

    /**
     * 开始播放。
     *
     * @param path      视频真实路径
     * @param skippable 是否允许点击/按键跳过
     * @return true 表示已经接管播放；false 表示无法播放，调用方应当忽略本次请求
     */
    public boolean play(String path, boolean skippable) {
        if (host == null || host.isFinishing() || host.isDestroyed()) return false;
        if (path == null || path.isEmpty()) return false;

        // 上一段还没结束就来了新的（脚本连播），先收掉旧的再开始。
        if (isPlaying()) dismiss();

        File file = new File(path);
        if (!file.isFile() || !file.canRead()) {
            Log.w(TAG, "video not readable: " + path);
            return false;
        }

        this.skippable = skippable;
        this.dismissed = false;

        // 播片期间申请音频焦点，让系统压低引擎 BGM。
        // 上游引擎的 Android 分支不会自己停音乐（见 OnsAudioFocus 注释）。
        OnsAudioFocus.acquire(host);

        try {
            container = new FrameLayout(host);
            container.setBackgroundColor(Color.BLACK);
            // 吃掉落在视频层上的触摸，避免穿透到下面的 SDL surface
            // 让游戏在播片时误收到推进文本的点击。
            container.setClickable(true);
            container.setFocusable(true);

            videoView = new OnsIjkVideoView(host);
            FrameLayout.LayoutParams videoLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER);
            videoView.setCallback(new OnsIjkVideoView.Callback() {
                @Override public void onFinished() {
                    Log.i(TAG, "video finished");
                    postDismiss();
                }

                @Override public void onFailed(int what, int extra) {
                    // 播不了就直接收场回到游戏，绝不停在黑屏上。
                    Log.w(TAG, "video failed what=" + what + " extra=" + extra);
                    postDismiss();
                }
            });
            container.addView(videoView, videoLp);

            if (skippable) container.addView(buildSkipHint(), buildSkipHintLp());

            container.setOnTouchListener((v, e) -> {
                if (this.skippable && e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    Log.i(TAG, "skipped by touch");
                    postDismiss();
                }
                return true;
            });

            // 与虚拟按键层同理：SDL 的 content root 是 RelativeLayout，
            // 传泛型 ViewGroup.LayoutParams 会被兜底成 WRAP_CONTENT，
            // 视频层拿不到全屏尺寸。
            host.addContentView(container, new android.widget.RelativeLayout.LayoutParams(
                    android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                    android.widget.RelativeLayout.LayoutParams.MATCH_PARENT));

            // 不再隐藏 SDL 的 surface：视频层现在是 TextureView，走普通视图合成，
            // 天然盖在 SDL 的 SurfaceView 之上，没有层级竞争。
            // 之前隐藏 SDL surface 反而触发重新布局，导致视频 surface 拿到畸变尺寸
            // （setBuffersGeometry w=1278,h=959），缓冲区未填满而出现白边/紫边。

            // 优先用 fd：与作用域存储/SAF 场景保持一致，路径不可直接 open 时仍可用。
            ParcelFileDescriptor fd = null;
            try {
                fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (Throwable t) {
                Log.w(TAG, "open fd failed, fallback to path", t);
            }
            if (fd != null) {
                pfd = fd;
                videoView.playFd(fd.getFileDescriptor());
            } else {
                videoView.playPath(path);
            }
            Log.i(TAG, "overlay playing " + path + " skippable=" + skippable);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "start overlay failed", t);
            dismiss();
            return false;
        }
    }

    /** 用户按键跳过时由宿主调用。 */
    public void skipByKey() {
        if (!isPlaying() || !skippable) return;
        Log.i(TAG, "skipped by key");
        postDismiss();
    }

    /** 宿主进入后台时调用：暂停解码，避免无谓耗电与音频抢占。 */
    public void onHostPause() {
        if (videoView != null) videoView.pausePlayback();
    }

    /** 移除覆盖层并释放播放器，回到游戏画面。 */
    public void dismiss() {
        if (dismissed) return;
        dismissed = true;

        // 无论正常播完、跳过还是启动失败，都要释放音频焦点让 BGM 恢复。
        // dismiss 是所有退出路径的汇合点，放这里能保证不漏。
        OnsAudioFocus.release();

        if (videoView != null) {
            try { videoView.release(); } catch (Throwable ignored) { }
            videoView = null;
        }
        if (container != null) {
            try {
                ViewGroup parent = (ViewGroup) container.getParent();
                if (parent != null) parent.removeView(container);
            } catch (Throwable t) {
                Log.w(TAG, "remove container failed", t);
            }
            container = null;
        }
        skipHint = null;
        if (pfd != null) {
            try { pfd.close(); } catch (Throwable ignored) { }
            pfd = null;
        }
        Log.i(TAG, "overlay dismissed");
    }

    /**
     * ijk 的回调可能来自解码线程，视图操作必须切回主线程。
     */
    private void postDismiss() {
        main.post(() -> {
            if (host == null || host.isFinishing() || host.isDestroyed()) return;
            dismiss();
        });
    }

    // ==================== 跳过提示 ====================

    private TextView buildSkipHint() {
        TextView tv = new TextView(host);
        tv.setText("轻触画面跳过");
        tv.setTextColor(Color.argb(200, 255, 255, 255));
        tv.setTextSize(13);
        int padH = dp(12), padV = dp(6);
        tv.setPadding(padH, padV, padH, padV);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.argb(110, 0, 0, 0));
        tv.setBackground(bg);
        skipHint = tv;
        // 播放几秒后淡出，避免一直压在画面上影响观看。
        main.postDelayed(this::fadeOutSkipHint, 3500);
        return tv;
    }

    private FrameLayout.LayoutParams buildSkipHintLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM);
        lp.rightMargin = dp(18);
        lp.bottomMargin = dp(18);
        return lp;
    }

    private void fadeOutSkipHint() {
        final TextView tv = skipHint;
        if (tv == null || dismissed) return;
        try {
            tv.animate().alpha(0f).setDuration(600).withEndAction(() -> {
                if (tv.getParent() != null) tv.setVisibility(View.GONE);
            }).start();
        } catch (Throwable t) {
            tv.setVisibility(View.GONE);
        }
    }

    private int dp(int v) {
        return Math.round(v * host.getResources().getDisplayMetrics().density);
    }
}