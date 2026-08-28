package com.yuki.yukihub.ons;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.io.FileDescriptor;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * 基于 ijkplayer（ffmpeg 软解）的视频视图，用于播放 ONS 游戏视频。
 *
 * 为什么用 TextureView 而不是 SurfaceView：
 * ONScripter 里 SDL 已经占了一个 SurfaceView。实测两个 SurfaceView 并存时：
 *   1. z 序由系统决定，视频层会被 SDL 层盖住（表现为黑屏有声音）；
 *      setZOrderMediaOverlay 压不住先创建的普通 surface。
 *   2. 尝试隐藏 SDL 层后触发重新布局，视频 surface 拿到畸变尺寸
 *      （日志可见 setBuffersGeometry w=1278,h=959），缓冲区未填满
 *      而露出未初始化内存，表现为白边 / 紫边。
 * TextureView 走普通视图合成，不占独立 surface 层，因此不存在层级竞争，
 * 也不会有 surface 尺寸协商问题。代价是合成开销略高，
 * 但 ONS 视频普遍是 800x600 这类小尺寸，完全够用。
 *
 * 解码格式：ONS 游戏视频多为 MPEG-1 / MPEG-4，系统 MediaPlayer 不支持
 * MPEG-PS 容器（会返回 error(1,-2147483648)），所以固定走 ffmpeg 软解。
 */
public class OnsIjkVideoView extends TextureView implements TextureView.SurfaceTextureListener {

    /** 播放结束或失败的统一回调。 */
    public interface Callback {
        /** 正常播完。 */
        void onFinished();

        /** 播放失败，what/extra 为 ijk 原始错误码，仅用于日志。 */
        void onFailed(int what, int extra);
    }

    private static final String TAG = "OnsIjkVideo";

    private IjkMediaPlayer player;
    private Callback callback;
    private Surface surface;
    private FileDescriptor pendingFd;
    private String pendingPath;
    private float volume = 1f;
    /** surface 就绪前收到的播放请求要缓存，等 onSurfaceTextureAvailable 再开始。 */
    private boolean surfaceReady;
    private boolean startRequested;
    private boolean released;
    /** 保证回调只触发一次，避免上层被通知两次。 */
    private boolean notified;
    private int videoWidth;
    private int videoHeight;

    public OnsIjkVideoView(Context context) {
        super(context);
        // 不透明：视频铺满整个视图，开启透明合成只会白费性能。
        setOpaque(true);
        setSurfaceTextureListener(this);
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    /** 音量，0f~1f。 */
    public void setVolume(float v) {
        volume = v < 0f ? 0f : (v > 1f ? 1f : v);
        if (player != null) {
            try {
                player.setVolume(volume, volume);
            } catch (Throwable t) {
                Log.w(TAG, "setVolume failed", t);
            }
        }
    }

    /** 用文件描述符播放，优先方式：不受 SAF / 作用域存储路径可见性影响。 */
    public void playFd(FileDescriptor fd) {
        pendingFd = fd;
        pendingPath = null;
        startRequested = true;
        maybeOpen();
    }

    /** 用真实路径播放，作为 fd 不可用时的退路。 */
    public void playPath(String path) {
        pendingFd = null;
        pendingPath = path;
        startRequested = true;
        maybeOpen();
    }

    private void maybeOpen() {
        if (!surfaceReady || !startRequested || released) return;
        startRequested = false;
        openPlayer();
    }

    private void openPlayer() {
        releasePlayer();
        try {
            IjkMediaPlayer p = new IjkMediaPlayer();

            // 锁定 ffmpeg 软解：MPEG-1 等老容器硬解普遍不支持。
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
            // 本地文件不需要网络重连逻辑。
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 0);
            // 老片源常有轻微损坏，允许丢帧而不是直接报错退出。
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek");
            // 只放开本地协议，避免脚本里塞 URL 造成意外外联。
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "file,pipe,fd,crypto,cache,async,data");

            p.setOnPreparedListener(preparedListener);
            p.setOnCompletionListener(completionListener);
            p.setOnErrorListener(errorListener);
            p.setOnVideoSizeChangedListener(videoSizeListener);
            if (surface != null) p.setSurface(surface);
            p.setScreenOnWhilePlaying(true);
            p.setVolume(volume, volume);

            if (pendingFd != null) {
                p.setDataSource(pendingFd);
            } else if (pendingPath != null && !pendingPath.isEmpty()) {
                p.setDataSource(pendingPath);
            } else {
                Log.w(TAG, "no data source");
                notifyFailed(-1, 0);
                return;
            }

            player = p;
            p.prepareAsync();
            Log.i(TAG, "ijk prepareAsync, fd=" + (pendingFd != null) + " path=" + pendingPath);
        } catch (Throwable t) {
            Log.e(TAG, "open ijk player failed", t);
            releasePlayer();
            notifyFailed(-2, 0);
        }
    }

    private final IMediaPlayer.OnPreparedListener preparedListener = mp -> {
        try {
            final int w = mp.getVideoWidth();
            final int h = mp.getVideoHeight();
            Log.i(TAG, "ijk prepared " + w + "x" + h);
            // 回调来自 ijk 的消息线程，视图操作必须回主线程。
            post(() -> applyVideoSize(w, h));
            mp.start();
        } catch (Throwable t) {
            Log.w(TAG, "start after prepared failed", t);
            notifyFailed(-3, 0);
        }
    };

    private final IMediaPlayer.OnCompletionListener completionListener = mp -> {
        Log.i(TAG, "ijk completed");
        notifyFinished();
    };

    private final IMediaPlayer.OnErrorListener errorListener = (mp, what, extra) -> {
        Log.e(TAG, "ijk error what=" + what + " extra=" + extra);
        notifyFailed(what, extra);
        return true;
    };

    private final IMediaPlayer.OnVideoSizeChangedListener videoSizeListener =
            (mp, w, h, sarNum, sarDen) -> post(() -> applyVideoSize(w, h));

    /** 在主线程更新视频尺寸并重新布局。 */
    private void applyVideoSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w == videoWidth && h == videoHeight) return;
        videoWidth = w;
        videoHeight = h;
        requestLayout();
    }

    public void pausePlayback() {
        try {
            if (player != null && player.isPlaying()) player.pause();
        } catch (Throwable t) {
            Log.w(TAG, "pause failed", t);
        }
    }

    public void release() {
        released = true;
        // 只释放播放器，不主动释放 Surface。
        //
        // 原因：TextureView 的 Surface 是包在 SurfaceTexture 上的，而 SurfaceTexture
        // 归 HWUI 渲染线程所有。在这里主动 release() 会让渲染线程操作已销毁的对象，
        // 实测导致退出游戏时崩溃：
        //   FORTIFY: pthread_mutex_lock called on a destroyed mutex
        //   Fatal signal 6 (SIGABRT) in tid xxxxx (hwuiTask1)
        // 正确时机是 onSurfaceTextureDestroyed 回调——那时系统已保证渲染线程不再使用它。
        releasePlayer();
        // 只解引用，不销毁底层对象。
        surface = null;
        surfaceReady = false;
    }

    private void releasePlayer() {
        IjkMediaPlayer p = player;
        player = null;
        if (p == null) return;
        try {
            p.setOnPreparedListener(null);
            p.setOnCompletionListener(null);
            p.setOnErrorListener(null);
            p.setOnVideoSizeChangedListener(null);
            p.setSurface(null);
            p.reset();
            p.release();
        } catch (Throwable t) {
            Log.w(TAG, "release player failed", t);
        }
    }

    private void notifyFinished() {
        if (notified) return;
        notified = true;
        Callback cb = callback;
        if (cb != null) cb.onFinished();
    }

    private void notifyFailed(int what, int extra) {
        if (notified) return;
        notified = true;
        Callback cb = callback;
        if (cb != null) cb.onFailed(what, extra);
    }

    // ==================== TextureView.SurfaceTextureListener ====================

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = new Surface(st);
        surfaceReady = true;
        Log.i(TAG, "surface texture available " + width + "x" + height);
        if (player != null) {
            try {
                player.setSurface(surface);
            } catch (Throwable t) {
                Log.w(TAG, "attach surface failed", t);
            }
        }
        maybeOpen();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        // TextureView 尺寸变化由视图系统负责缩放，播放器无需干预。
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        surfaceReady = false;
        if (player != null) {
            try {
                player.setSurface(null);
            } catch (Throwable t) {
                Log.w(TAG, "detach surface failed", t);
            }
        }
        // 这里才是释放 Surface 的正确时机：系统保证渲染线程已不再使用该 SurfaceTexture。
        Surface s = surface;
        surface = null;
        if (s != null) {
            try {
                s.release();
            } catch (Throwable t) {
                Log.w(TAG, "release surface failed", t);
            }
        }
        // 返回 true 表示由系统释放 SurfaceTexture。
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
        // 每帧都会回调，这里不需要做任何事。
    }

    // ==================== 等比缩放 ====================

    /**
     * 按视频原始宽高比做 letterbox，避免 4:3 老片源被拉成全屏变形。
     * 视频尺寸未知时退化为使用父容器给的尺寸。
     */
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = View.getDefaultSize(videoWidth, widthMeasureSpec);
        int height = View.getDefaultSize(videoHeight, heightMeasureSpec);
        if (videoWidth > 0 && videoHeight > 0 && width > 0 && height > 0) {
            long videoRatio = (long) videoWidth * height;
            long viewRatio = (long) width * videoHeight;
            if (videoRatio > viewRatio) {
                height = width * videoHeight / videoWidth;
            } else if (videoRatio < viewRatio) {
                width = height * videoWidth / videoHeight;
            }
        }
        setMeasuredDimension(width, height);
    }
}