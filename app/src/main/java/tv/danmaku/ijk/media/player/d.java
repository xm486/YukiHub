package tv.danmaku.ijk.media.player;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.lang.ref.WeakReference;
import tv.danmaku.ijk.media.player.pragma.DebugLog;

/* JADX INFO: loaded from: classes.dex */
public final class d extends Handler {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final WeakReference f20726a;

    public d(IjkMediaPlayer ijkMediaPlayer, Looper looper) {
        super(looper);
        this.f20726a = new WeakReference(ijkMediaPlayer);
    }

    @Override // android.os.Handler
    public final void handleMessage(Message message) {
        IjkMediaPlayer ijkMediaPlayer = (IjkMediaPlayer) this.f20726a.get();
        if (ijkMediaPlayer != null) {
            if (ijkMediaPlayer.mNativeMediaPlayer != 0) {
                int i8 = message.what;
                if (i8 != 0) {
                    if (i8 == 1) {
                        ijkMediaPlayer.notifyOnPrepared();
                        return;
                    }
                    if (i8 == 2) {
                        ijkMediaPlayer.stayAwake(false);
                        ijkMediaPlayer.notifyOnCompletion();
                        return;
                    }
                    if (i8 == 3) {
                        long j = message.arg1;
                        if (j < 0) {
                            j = 0;
                        }
                        long duration = ijkMediaPlayer.getDuration();
                        long j8 = duration > 0 ? (j * 100) / duration : 0L;
                        ijkMediaPlayer.notifyOnBufferingUpdate((int) (j8 < 100 ? j8 : 100L));
                        return;
                    }
                    if (i8 == 4) {
                        ijkMediaPlayer.notifyOnSeekComplete();
                        return;
                    }
                    if (i8 == 5) {
                        ijkMediaPlayer.mVideoWidth = message.arg1;
                        ijkMediaPlayer.mVideoHeight = message.arg2;
                        ijkMediaPlayer.notifyOnVideoSizeChanged(ijkMediaPlayer.mVideoWidth, ijkMediaPlayer.mVideoHeight, ijkMediaPlayer.mVideoSarNum, ijkMediaPlayer.mVideoSarDen);
                        return;
                    }
                    if (i8 == 99) {
                        if (message.obj == null) {
                            ijkMediaPlayer.notifyOnTimedText(null);
                            return;
                        } else {
                            ijkMediaPlayer.notifyOnTimedText(new IjkTimedText(new Rect(0, 0, 1, 1), (String) message.obj));
                            return;
                        }
                    }
                    if (i8 == 100) {
                        DebugLog.e(IjkMediaPlayer.TAG, "Error (" + message.arg1 + "," + message.arg2 + ")");
                        if (!ijkMediaPlayer.notifyOnError(message.arg1, message.arg2)) {
                            ijkMediaPlayer.notifyOnCompletion();
                        }
                        ijkMediaPlayer.stayAwake(false);
                        return;
                    }
                    if (i8 == 200) {
                        if (message.arg1 == 3) {
                            DebugLog.i(IjkMediaPlayer.TAG, "Info: MEDIA_INFO_VIDEO_RENDERING_START\n");
                        }
                        ijkMediaPlayer.notifyOnInfo(message.arg1, message.arg2);
                        return;
                    } else if (i8 == 10001) {
                        ijkMediaPlayer.mVideoSarNum = message.arg1;
                        ijkMediaPlayer.mVideoSarDen = message.arg2;
                        ijkMediaPlayer.notifyOnVideoSizeChanged(ijkMediaPlayer.mVideoWidth, ijkMediaPlayer.mVideoHeight, ijkMediaPlayer.mVideoSarNum, ijkMediaPlayer.mVideoSarDen);
                        return;
                    } else {
                        DebugLog.e(IjkMediaPlayer.TAG, "Unknown message type " + message.what);
                        return;
                    }
                }
                return;
            }
        }
        DebugLog.w(IjkMediaPlayer.TAG, "IjkMediaPlayer went away with unhandled events");
    }
}
