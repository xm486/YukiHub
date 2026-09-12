package com.yuki.yukihub.bigscreen;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import com.yuki.yukihub.R;

/**
 * 大屏入场动画（spec §S1）。
 *
 * <p>序列（总长约 1.6s，任意输入可跳过）：
 * <ol>
 *   <li>深色底 + 中央 logo：淡入 + 上浮 28dp（520ms）</li>
 *   <li>一条光带自左向右扫过（780ms，峰值透明度 0.55）</li>
 *   <li>logo 上浮淡出（240ms）</li>
 *   <li>入场层淡出，同时主界面做<b>圆形揭示</b>（0 → 全屏半径，560ms）+ 轻微回缩 1.06 → 1.0</li>
 * </ol>
 *
 * <p>低性能档 / 关闭动画时：直接把入场层收掉、主界面立刻到位（不动画）。
 * 这样"高级感"是加分项而不是负担。
 */
public class BigScreenIntro {

    public interface Listener {
        /** 入场结束（主界面已完全可见），此时才应该接管输入 */
        void onIntroFinished();
    }

    // 时间轴（毫秒）
    private static final long T_LOGO_IN = 80L;
    private static final long D_LOGO_IN = 520L;
    private static final long T_BAND = 300L;
    private static final long D_BAND = 780L;
    private static final long T_LOGO_OUT = 980L;
    private static final long D_LOGO_OUT = 240L;
    private static final long T_REVEAL = 1120L;
    private static final long D_REVEAL = 560L;

    private final Activity activity;
    private final View introRoot;
    private final View logoBox;
    private final View band;
    private final View contentRoot;
    private final Listener listener;
    private final boolean richEffects;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean playing = false;
    private boolean finished = false;

    /**
     * M18-2：**入场视频**（用户要的：可以用自己选的视频当开场）。
     *
     * <p>为空 = 用内置动画（原来的那套，一个字都没改回去）。
     * 非空 = 播放这个视频，铺满屏幕；播完、或任意输入（skip）即进入主界面。
     */
    private String videoUri = "";
    private android.view.TextureView videoView;
    private android.media.MediaPlayer videoPlayer;

    /** M18-2：设置入场视频（空 = 用内置动画） */
    public void setVideoUri(String uri) {
        this.videoUri = uri == null ? "" : uri.trim();
    }

    public boolean hasVideo() { return !videoUri.isEmpty() && !videoFailed; }

    /** M18-2：视频播失败过就不再重试（否则 onError → 重播 → 又失败，会死循环） */
    private boolean videoFailed = false;

    public BigScreenIntro(Activity activity, View introRoot, View logoBox, View band,
                          View contentRoot, boolean richEffects, Listener listener) {
        this.activity = activity;
        this.introRoot = introRoot;
        this.logoBox = logoBox;
        this.band = band;
        this.contentRoot = contentRoot;
        this.richEffects = richEffects;
        this.listener = listener;
    }

    public boolean isPlaying() { return playing; }

    public boolean isFinished() { return finished; }

    /** 开始播放 */
    public void start() {
        if (playing || finished) { return; }
        playing = true;

        introRoot.setVisibility(View.VISIBLE);
        introRoot.setAlpha(1f);
        introRoot.bringToFront();

        if (!richEffects) {
            // 低性能档：不做动画，直接到位
            handler.postDelayed(this::finishNow, 120L);
            return;
        }

        // M18-2：用户选了入场视频 → 直接播视频（内置动画完全不参与）
        if (hasVideo() && startVideo()) { return; }

        contentRoot.setVisibility(View.VISIBLE);
        contentRoot.setAlpha(0f);
        contentRoot.setScaleX(1.06f);
        contentRoot.setScaleY(1.06f);

        logoBox.setAlpha(0f);
        logoBox.setTranslationY(dp(28));

        band.setAlpha(0f);
        band.setTranslationX(-dp(220));

        handler.postDelayed(() -> {
            if (!playing) { return; }
            logoBox.animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(D_LOGO_IN)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }, T_LOGO_IN);

        handler.postDelayed(() -> {
            if (!playing) { return; }
            band.animate()
                    .translationX(dp(280))
                    .setDuration(D_BAND)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
            band.animate().alpha(0.55f).setDuration(160L).start();
            handler.postDelayed(() -> {
                if (playing) { band.animate().alpha(0f).setDuration(380L).start(); }
            }, 340L);
        }, T_BAND);

        handler.postDelayed(() -> {
            if (!playing) { return; }
            logoBox.animate()
                    .alpha(0f).translationY(-dp(14))
                    .setDuration(D_LOGO_OUT)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }, T_LOGO_OUT);

        handler.postDelayed(() -> {
            if (!playing) { return; }
            introRoot.animate().alpha(0f).setDuration(260L)
                    .withEndAction(() -> {
                        introRoot.setVisibility(View.GONE);
                        revealContent();
                    }).start();
        }, T_REVEAL);
    }

    /**
     * M18-2：启动入场视频。返回 false 表示没起来（调用方回退到内置动画）。
     *
     * <p>视频播完即 finishNow()；任意输入走 skip()（= 立刻进主界面，不等播完）。
     */
    private boolean startVideo() {
        try {
            if (videoView == null && introRoot != null) {
                videoView = introRoot.findViewById(R.id.bsIntroVideo);
            }
            if (videoView == null) { return false; }
            videoView.setVisibility(View.VISIBLE);
            videoView.setAlpha(1f);
            // 内置动画的零件全部藏起来，只留视频
            if (logoBox != null) { logoBox.setAlpha(0f); }
            if (band != null) { band.setAlpha(0f); }
            if (contentRoot != null) {
                contentRoot.setVisibility(View.INVISIBLE);
                contentRoot.setAlpha(0f);
            }
            final android.view.TextureView tv = videoView;
            if (tv.getSurfaceTexture() != null) {
                playVideoOn(tv);
            } else {
                // TextureView 刚显示时还没有 SurfaceTexture（要等一次布局），
                // 直接 setSurface(null) 会抛异常 → 会白白退回内置动画。
                // 正确做法：等 onSurfaceTextureAvailable 再起播。
                tv.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                        playVideoOn(tv);
                    }
                    @Override
                    public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) { }
                    @Override
                    public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                        stopVideo();
                        return true;
                    }
                    @Override
                    public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { }
                });
                // 兜底：2 秒还没 surface 就退回内置动画，别把用户卡在黑屏
                handler.postDelayed(() -> {
                    if (playing && videoPlayer == null) { replay(); }
                }, 2000L);
            }
            return true;
        } catch (Throwable t) {
            stopVideo();
            return false;
        }
    }

    /** M18-2：在已就绪的 TextureView 上真正起播（失败则退回内置动画） */
    private void playVideoOn(final android.view.TextureView tv) {
        if (!playing || videoPlayer != null || tv == null) { return; }
        try {
            android.graphics.SurfaceTexture st = tv.getSurfaceTexture();
            if (st == null) { return; }
            videoPlayer = new android.media.MediaPlayer();
            videoPlayer.setDataSource(activity, android.net.Uri.parse(videoUri));
            videoPlayer.setSurface(new android.view.Surface(st));
            videoPlayer.setLooping(false);
            videoPlayer.setOnPreparedListener(mp -> {
                if (!playing) { return; }
                try { mp.start(); } catch (Throwable ignored) { }
            });
            videoPlayer.setOnCompletionListener(mp -> {
                if (playing) { finishNow(); }   // 播完 → 进主界面
            });
            videoPlayer.setOnErrorListener((mp, what, extra) -> {
                // 视频坏了：退回内置动画，别把用户卡在开场
                syncVideoFailed();
                return true;
            });
            videoPlayer.prepareAsync();
        } catch (Throwable t) {
            syncVideoFailed();
        }
    }

    /** M18-2：视频路径失败 → 收掉视频、标记失败、重走内置动画（只走一次，防止死循环） */
    private void syncVideoFailed() {
        stopVideo();
        if (videoView != null) { videoView.setVisibility(View.GONE); }
        if (playing) { replay(); }
    }

    /** M18-2：视频起不来时的兜底 —— 重走内置动画（并标记视频失败，避免死循环） */
    private void replay() {
        videoFailed = true;
        playing = false;
        start();
    }

    private void stopVideo() {
        if (videoPlayer == null) { return; }
        final android.media.MediaPlayer mp = videoPlayer;
        videoPlayer = null;
        try {
            mp.setOnCompletionListener(null);
            mp.setOnErrorListener(null);
            mp.stop();
        } catch (Throwable ignored) { }
        try { mp.release(); } catch (Throwable ignored) { }
    }

    private void hideVideo() {
        stopVideo();
        if (videoView != null) { videoView.setVisibility(View.GONE); }
    }

    /** 任意输入跳过：立刻到结束状态 */
    public void skip() {
        if (!playing || finished) { return; }
        finishNow();
    }

    private void revealContent() {
        if (finished) { return; }
        contentRoot.setVisibility(View.VISIBLE);
        contentRoot.setAlpha(1f);

        int w = contentRoot.getWidth();
        int h = contentRoot.getHeight();
        if (w <= 0 || h <= 0) {
            // 还没测量出来：直接到位（罕见，兜底不崩）
            contentRoot.setScaleX(1f);
            contentRoot.setScaleY(1f);
            finish();
            return;
        }
        float maxRadius = (float) Math.hypot(w, h) / 2f;
        try {
            android.animation.Animator reveal = ViewAnimationUtils.createCircularReveal(
                    contentRoot, w / 2, h / 2, 0f, maxRadius);
            reveal.setDuration(D_REVEAL);
            reveal.setInterpolator(new DecelerateInterpolator());
            reveal.start();
        } catch (Throwable ignored) { }

        contentRoot.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(D_REVEAL + 60L)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        handler.postDelayed(this::finish, D_REVEAL);
    }

    private void finishNow() {
        playing = false;
        hideVideo();   // M18-2：入场视频收掉（否则会盖在主界面上）
        if (band != null) { band.animate().cancel(); band.setAlpha(0f); }
        if (logoBox != null) { logoBox.animate().cancel(); logoBox.setAlpha(0f); }
        if (introRoot != null) {
            introRoot.animate().cancel();
            introRoot.setVisibility(View.GONE);
            introRoot.setAlpha(1f);
        }
        if (contentRoot != null) {
            contentRoot.setVisibility(View.VISIBLE);
            contentRoot.setAlpha(1f);
            contentRoot.setScaleX(1f);
            contentRoot.setScaleY(1f);
        }
        finish();
    }

    private void finish() {
        if (finished) { return; }
        finished = true;
        playing = false;
        if (listener != null) { listener.onIntroFinished(); }
    }

    /** 页面销毁时清理回调 */
    public void cancel() {
        playing = false;
        hideVideo();   // M18-2：页面销毁时把入场视频的解码器放掉
        handler.removeCallbacksAndMessages(null);
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}