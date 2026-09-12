package com.yuki.yukihub.bigscreen;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;

import java.io.File;

/**
 * 预告视频播放器（对应 bigscreen_spec.md §S10.4）。
 *
 * <p>行为与防护：
 * <ul>
 *   <li><b>悬停防抖</b>：焦点变化会取消上一次 pending，只有停够 {@code delayMs} 才真正起播</li>
 *   <li><b>先准备再淡入</b>：{@code onPrepared} 之后才 fade in，避免黑帧闪一下</li>
 *   <li><b>3s 超时</b>：准备不出来直接放弃（不阻塞、不报错）</li>
 *   <li><b>单实例</b>：起播前必 release 旧 player，绝不叠加解码器</li>
 *   <li><b>等比裁切</b>：复刻 MainActivity 的 center-crop 变换，视频不变形</li>
 *   <li><b>可换目标</b>：{@link #attach(TextureView)} 可在主背景 / 详情层背景之间切换</li>
 * </ul>
 */
public class TrailerPlayer {

    public interface Listener {
        /** 真正开始播放（已淡入） */
        void onTrailerStarted(String path);

        /** 失败（文件不存在 / 解码失败 / 超时） */
        void onTrailerFailed(String path, String reason);
    }

    private static final long PREPARE_TIMEOUT_MS = 3000L;
    private static final long FADE_IN_MS = 600L;
    private static final float VIDEO_ALPHA = 1f;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextureView target;
    private MediaPlayer player;
    private boolean prepared = false;

    private String pendingPath;
    private Runnable pendingTask;
    private long token = 0;
    private boolean muted = true;
    /** M16：当前正在播的视频源 —— 同源切换输出目标时**不重播**（进详情页/全屏预览不再从头开始） */
    private String currentPath;
    /** M16-1：续播位置（切换输出目标后 seekTo 到这里，避免"从头重播"） */
    private int resumeMs = 0;
    /** M16-1：正在 prepare 的源 —— 防止同源的 request 把正在准备的播放打断（会从 0 重来） */
    private String preparingPath;
    /**
     * M17：**无缝交接**用的旧实例。
     *
     * <p>切换输出目标时不再"先停再起"（那会带来一段黑屏 + 重新 prepare 的延迟），
     * 而是让旧实例**继续播**，同时在新目标上 prepare 新实例；新实例就绪后才释放旧实例。
     * 观感上就是"画面没断，只是换了个地方显示"。
     */
    private MediaPlayer handoffOld;
    /** M17：交接后的首次显示不做淡入（避免又"黑一下"） */
    private boolean instantShow = false;

    public TrailerPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * 切换输出目标。
     *
     * <p>M16-1：**放弃 setSurface 迁移**。上一版我试着把输出面直接搬到新的 TextureView，
     * 结果 logcat 显示旧面被销毁（{@code SkiaOpenGLPipeline::setSurface: surface=NULL}）
     * 而新面没接上 —— 表现就是"有声音但画面卡住/只剩背景"。
     *
     * <p>现在改成**记住播放位置 → 在新目标上续播**：位置连续（不会从头开始），
     * 画面也一定由新目标自己的 SurfaceTexture 正常输出。
     */
    public void attach(TextureView newTarget) {
        if (target == newTarget) { return; }
        final String path = currentPath;
        final boolean wasPlaying = player != null && prepared && path != null;
        final int pos = wasPlaying ? currentPosition() : 0;
        target = newTarget;
        if (wasPlaying && newTarget != null) {
            // M17：无缝交接 —— 旧实例先留着（继续在原画面上播），新实例 prepare 成功后再释放
            releaseHandoff();
            handoffOld = player;
            player = null;
            prepared = false;
            currentPath = null;
            preparingPath = null;
            resumeMs = pos;
            instantShow = true;
            startInternal(path);
        } else {
            cancel();
        }
    }

    /** 释放"交接中"的旧实例（正常路径下在新实例 prepared 之后） */
    private void releaseHandoff() {
        if (handoffOld == null) { return; }
        final MediaPlayer old = handoffOld;
        handoffOld = null;
        handler.postDelayed(() -> safeRelease(old), 60L);
    }

    /** 当前播放位置（失败返回 0） */
    private int currentPosition() {
        try {
            return player != null ? player.getCurrentPosition() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        try {
            if (player != null) { player.setVolume(muted ? 0f : 0.6f, muted ? 0f : 0.6f); }
        } catch (Throwable ignored) { }
    }

    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 请求延迟播放（悬停防抖）。重复调用会重置计时。
     */
    public void request(String path, long delayMs) {
        if (path == null || path.trim().isEmpty()) { cancel(); return; }
        // M16：同一个源正在播（或已就绪）→ **什么都不做**，不要从头重播。
        // M16-1：**正在 prepare 的同源也直接返回** —— 否则刚由 attach() 发起的续播会被打断，
        //        重新从 0 开始（这就是"进详情页要重播"的另一半原因）。
        if (path.equals(currentPath) && player != null && prepared) { return; }
        if (path.equals(preparingPath)) { return; }
        cancel();
        pendingPath = path;
        pendingTask = () -> {
            pendingTask = null;
            startInternal(path);
        };
        handler.postDelayed(pendingTask, Math.max(0L, delayMs));
    }

    /** 取消 pending 并停止播放（淡出后释放） */
    public void cancel() {
        if (pendingTask != null) {
            handler.removeCallbacks(pendingTask);
            pendingTask = null;
        }
        pendingPath = null;
        currentPath = null;   // M16：停了就不再"算正在播"
        preparingPath = null; // M16-1：取消后也不再有"准备中"的源
        instantShow = false;
        releaseHandoff();     // M17：取消时交接中的旧实例也必须放掉（否则泄漏解码器）
        token++;
        if (player == null) { return; }
        final MediaPlayer old = player;
        player = null;
        prepared = false;
        if (target != null) {
            target.animate().alpha(0f).setDuration(160L).start();
        }
        handler.postDelayed(() -> safeRelease(old), 200L);
    }

    /** 彻底释放（页面销毁 / 启动游戏前） */
    public void release() {
        cancel();
        safeRelease(player);
        player = null;
        prepared = false;
    }

    // ================= 内部 =================

    /** 远程视频直链（M11）：http(s) 开头的一律当网络源处理 */
    public static boolean isRemote(String path) {
        if (path == null) { return false; }
        String p = path.trim().toLowerCase(java.util.Locale.US);
        return p.startsWith("http://") || p.startsWith("https://");
    }

    private void startInternal(final String path) {
        if (target == null) { return; }
        // 远程直链（M11）：不检查本地文件；本地视频才判存在
        if (!isRemote(path) && !new File(path).exists()) {
            if (listener != null) { listener.onTrailerFailed(path, "missing"); }
            return;
        }

        final long myToken = ++token;
        preparingPath = path;   // M16-1：标记"这个源正在准备"，同源的 request 不会打断它
        // M16-1：续播位置是"一次性"的 —— 在这里就取走并清零，
        // 避免起播失败时残留下来，下次换游戏被误 seek 到别的位置。
        final int seekMs = resumeMs;
        resumeMs = 0;
        safeRelease(player);
        prepared = false;

        if (!target.isAvailable()) {
            // Surface 还没就绪：等它就绪再起播（只等一次，避免泄漏监听）
            target.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyFit());
            target.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    target.setSurfaceTextureListener(null);
                    if (myToken == token) { startInternal(path); }
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    if (myToken == token) { applyFit(); }
                }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
            });
            return;
        }

        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setDataSource(path);
            android.view.Surface surface = new android.view.Surface(target.getSurfaceTexture());
            mp.setSurface(surface);
            surface.release();
            mp.setLooping(true);
            mp.setVolume(muted ? 0f : 0.6f, muted ? 0f : 0.6f);

            mp.setOnPreparedListener(p -> {
                if (myToken != token) { safeRelease(p); return; }
                prepared = true;
                currentPath = path;   // M16：记下当前源，供"同源不重播"判断
                preparingPath = null;
                // M16-1：切换输出目标后从原位置继续（不从头播）
                if (seekMs > 0) {
                    try { p.seekTo(seekMs); } catch (Throwable ignored) { }
                }
                applyFit();
                try { p.start(); } catch (Throwable ignored) { }
                // M17：交接 —— 新实例已经在播了，这时才释放旧实例（画面不会断）
                releaseHandoff();
                if (instantShow) {
                    instantShow = false;
                    if (target != null) {
                        target.animate().cancel();
                        target.setAlpha(VIDEO_ALPHA);
                    }
                } else {
                    fadeIn();
                }
                if (listener != null) { listener.onTrailerStarted(path); }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                safeRelease(p);
                player = null;
                prepared = false;
                preparingPath = null;   // M16-1：失败后释放标记，否则同源永远无法重试
                releaseHandoff();       // M17：新实例起不来，交接中的旧实例也别留着
                instantShow = false;
                if (listener != null && myToken == token) {
                    listener.onTrailerFailed(path, "decode(" + what + "/" + extra + ")");
                }
                return true;
            });
            mp.prepareAsync();

            // 超时保护：3s 还没 prepared 就放弃
            handler.postDelayed(() -> {
                if (myToken == token && !prepared) {
                    safeRelease(player);
                    player = null;
                    preparingPath = null;   // M16-1：超时同样要释放标记
                    releaseHandoff();       // M17：超时也要放掉交接中的旧实例
                    instantShow = false;
                    if (listener != null) { listener.onTrailerFailed(path, "timeout"); }
                }
            }, PREPARE_TIMEOUT_MS);

        } catch (Throwable t) {
            safeRelease(player);
            player = null;
            if (listener != null) { listener.onTrailerFailed(path, String.valueOf(t.getMessage())); }
        }
    }

    private void fadeIn() {
        if (target == null) { return; }
        target.setAlpha(0f);
        target.animate().alpha(VIDEO_ALPHA).setDuration(FADE_IN_MS).start();
    }

    /** 等比裁切 / 原比例完整显示（M9：设置里可切） */
    private boolean fitInside = false;
    /** PV 显示方式：false = 铺满裁切（默认），true = 原比例完整显示（留黑边） */
    public void setFitMode(boolean fit) {
        this.fitInside = fit;
        applyFit();
    }

    /** 按当前模式应用变换：fill → 铺满裁切；fit → 等比缩放到完整可见 */
    private void applyFit() {
        if (target == null || player == null) { return; }
        try {
            int viewW = target.getWidth();
            int viewH = target.getHeight();
            int videoW = player.getVideoWidth();
            int videoH = player.getVideoHeight();
            if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) { return; }
            float scale = fitInside
                    ? Math.min((float) viewW / videoW, (float) viewH / videoH)
                    : Math.max((float) viewW / videoW, (float) viewH / videoH);
            float scaledW = videoW * scale;
            float scaledH = videoH * scale;
            Matrix matrix = new Matrix();
            matrix.setScale(scaledW / viewW, scaledH / viewH, viewW / 2f, viewH / 2f);
            target.setTransform(matrix);
        } catch (Throwable ignored) { }
    }

    private static void safeRelease(MediaPlayer mp) {
        if (mp == null) { return; }
        try { mp.stop(); } catch (Throwable ignored) { }
        try { mp.release(); } catch (Throwable ignored) { }
    }
}