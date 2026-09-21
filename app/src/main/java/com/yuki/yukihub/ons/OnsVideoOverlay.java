package com.yuki.yukihub.ons;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
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

    // ==================== 长按跳过 ====================

    /** 长按多久算「确认跳过」，单位毫秒。 */
    private static final long SKIP_HOLD_MS = 2000L;
    /** 圆环重绘间隔，约 60fps。 */
    private static final long RING_TICK_MS = 16L;
    /** 按下点位移超过该值（dp）视为「不是长按」，取消计时。 */
    private static final int SKIP_SLOP_DP = 24;

    /** 右下角进度圆环；未长按时为 GONE。 */
    private SkipRingView ring;
    /** 长按起始时间戳，0 表示当前没有进行中的长按。 */
    private long holdStartAt;
    /** 长按起始坐标，用于判定手指是否移开。 */
    private float holdDownX;
    private float holdDownY;
    private final Runnable holdTicker = new Runnable() {
        @Override public void run() {
            if (holdStartAt == 0L) return;
            long elapsed = android.os.SystemClock.uptimeMillis() - holdStartAt;
            float progress = (float) Math.min(1.0, (double) elapsed / SKIP_HOLD_MS);
            if (ring != null) ring.setProgress(progress);
            if (progress >= 1f) {
                cancelHold(false);
                triggerSkip();
                return;
            }
            main.postDelayed(this, RING_TICK_MS);
        }
    };

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

            if (skippable) {
                container.addView(buildSkipHint(), buildSkipHintLp());
                container.addView(buildRing(), buildRingLp());
            }

            container.setOnTouchListener(this::onContainerTouch);

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

    // ==================== 触摸：长按 3 秒跳过 ====================

    /**
     * 覆盖层的触摸处理。
     *
     * 为什么不是「按下即跳过」：视频多半盖在玩家正在读文本的位置上，
     * 点一下就跳会让误触代价极低（手指扫过屏幕就没了）。改成必须按住
     * 3 秒，配合右下角圆环给出明确的进度反馈，抬手或移开手指即取消。
     *
     * @return 恒为 true：吞掉所有触摸，避免穿透到下面的 SDL surface
     *         让游戏误收到推进文本的点击。
     */
    private boolean onContainerTouch(View v, MotionEvent e) {
        if (!skippable) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginHold(e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_MOVE:
                // 手指移开超过阈值就不算「按住不放」，直接取消，
                // 免得玩家划屏时把视频跳掉。
                if (holdStartAt != 0L && movedTooFar(e.getX(), e.getY())) {
                    Log.i(TAG, "hold cancelled by move");
                    cancelHold(true);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // 未数满 3 秒就松手：取消，不跳过。
                if (holdStartAt != 0L) {
                    Log.i(TAG, "hold released before threshold, not skipping");
                    cancelHold(true);
                }
                break;
            default:
                break;
        }
        return true;
    }

    private boolean movedTooFar(float x, float y) {
        float dx = x - holdDownX;
        float dy = y - holdDownY;
        int slop = dp(SKIP_SLOP_DP);
        return dx * dx + dy * dy > (float) slop * slop;
    }

    /** 开始计时并显示圆环。 */
    private void beginHold(float x, float y) {
        holdStartAt = android.os.SystemClock.uptimeMillis();
        holdDownX = x;
        holdDownY = y;
        if (ring != null) {
            ring.setProgress(0f);
            ring.setVisibility(View.VISIBLE);
        }
        main.removeCallbacks(holdTicker);
        main.postDelayed(holdTicker, RING_TICK_MS);
    }

    /**
     * 结束长按计时。
     *
     * @param animateOut 是否让圆环淡出（抬手/移开时淡出，跳过时直接隐藏）
     */
    private void cancelHold(boolean animateOut) {
        holdStartAt = 0L;
        main.removeCallbacks(holdTicker);
        final SkipRingView r = ring;
        if (r == null) return;
        r.setProgress(0f);
        if (!animateOut) {
            r.setVisibility(View.GONE);
            return;
        }
        r.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            r.setVisibility(View.GONE);
            r.setAlpha(1f);
        }).start();
    }

    /** 数满 3 秒：给一次震动反馈再跳过。 */
    private void triggerSkip() {
        Log.i(TAG, "skipped by long press (" + SKIP_HOLD_MS + "ms)");
        try {
            if (container != null) {
                container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        } catch (Throwable ignored) {
            // 部分机型/未开启触感反馈时可能抛异常，跳过时不该因此中断。
        }
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

        // 视频结束/跳过时，长按计时可能还在跑，必须停掉，
        // 否则 ring 被置空后 ticker 仍在重绘一个已移除的视图。
        holdStartAt = 0L;
        main.removeCallbacks(holdTicker);

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
        tv.setText("长按跳过");
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

    // ==================== 长按进度圆环 ====================

    /**
     * 创建右下角的进度圆环。
     *
     * 位置和「长按跳过」提示完全重合 —— 提示 3.5 秒后淡出消失，
     * 之后那块区域就只剩圆环在用，互不打架。
     */
    private SkipRingView buildRing() {
        SkipRingView v = new SkipRingView(host);
        v.setVisibility(View.GONE);
        ring = v;
        return v;
    }

    private FrameLayout.LayoutParams buildRingLp() {
        int size = dp(56);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                size, size, Gravity.END | Gravity.BOTTOM);
        lp.rightMargin = dp(12);
        lp.bottomMargin = dp(12);
        return lp;
    }

    /**
     * 长按进度的圆形指示器。
     *
     * 自绘而不是用 ProgressBar：需要的是「细环 + 从 12 点开始顺时针扫过」
     * 这种样式，ProgressBar 的 indeterminate 实现无法直接表达，
     * 而 drawArc 几行就够了。
     *
     * 画法：一层半透明的完整圆环当轨道，再叠一层按 progress 扫过的亮环。
     */
    private static final class SkipRingView extends View {

        /** 圆环直径占视图边长的比例，留白避免描边被裁掉。 */
        private static final float STROKE_DP = 3f;

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final float strokePx;

        /** 0f~1f。 */
        private float progress;

        SkipRingView(Context context) {
            super(context);
            strokePx = STROKE_DP * context.getResources().getDisplayMetrics().density;

            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(strokePx);
            trackPaint.setColor(Color.argb(70, 255, 255, 255));

            barPaint.setStyle(Paint.Style.STROKE);
            barPaint.setStrokeWidth(strokePx);
            barPaint.setStrokeCap(Paint.Cap.ROUND);
            barPaint.setColor(Color.argb(235, 255, 255, 255));
        }

        void setProgress(float p) {
            if (p < 0f) p = 0f;
            if (p > 1f) p = 1f;
            if (p == progress) return;
            progress = p;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float inset = strokePx;     // 描边有一半画在路径外侧，必须内缩
            oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
            // 底环常显，让玩家知道「这里有东西可以按」。
            canvas.drawArc(oval, 0f, 360f, false, trackPaint);
            if (progress <= 0f) return;
            // 从 12 点（-90°）开始顺时针扫。progress 接近 1 时用量保持
            // 在 360 以下，避免某些设备上传 0/360 的边界导致整圈闪断。
            canvas.drawArc(oval, -90f, 359.9f * progress, false, barPaint);
        }
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