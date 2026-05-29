package A4;

import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.MediaController;
import com.ies_net.artemis.VideoViewActivity;
import java.io.FileDescriptor;
import java.io.IOException;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class c extends SurfaceView implements MediaController.MediaPlayerControl {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final String f108a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    public final a f109b;

    /* JADX INFO: renamed from: c, reason: collision with root package name */
    public final a f110c;

    /* JADX INFO: renamed from: d, reason: collision with root package name */
    public final VideoViewActivity f111d;

    /* JADX INFO: renamed from: e, reason: collision with root package name */
    public int f112e;

    /* JADX INFO: renamed from: f, reason: collision with root package name */
    public int f113f;

    /* JADX INFO: renamed from: g, reason: collision with root package name */
    public int f114g;

    /* JADX INFO: renamed from: h, reason: collision with root package name */
    public final a f115h;

    /* JADX INFO: renamed from: i, reason: collision with root package name */
    public FileDescriptor f116i;
    public MediaController j;

    /* JADX INFO: renamed from: k, reason: collision with root package name */
    public IjkMediaPlayer f117k;

    /* JADX INFO: renamed from: l, reason: collision with root package name */
    public IMediaPlayer.OnCompletionListener f118l;

    /* JADX INFO: renamed from: m, reason: collision with root package name */
    public IMediaPlayer.OnErrorListener f119m;

    /* JADX INFO: renamed from: n, reason: collision with root package name */
    public IMediaPlayer.OnPreparedListener f120n;

    /* JADX INFO: renamed from: o, reason: collision with root package name */
    public final a f121o;

    /* JADX INFO: renamed from: p, reason: collision with root package name */
    public int f122p;

    /* JADX INFO: renamed from: q, reason: collision with root package name */
    public final a f123q;
    public int r;

    /* JADX INFO: renamed from: s, reason: collision with root package name */
    public SurfaceHolder f124s;

    /* JADX INFO: renamed from: t, reason: collision with root package name */
    public int f125t;

    /* JADX INFO: renamed from: u, reason: collision with root package name */
    public int f126u;

    /* JADX INFO: renamed from: v, reason: collision with root package name */
    public int f127v;

    /* JADX INFO: renamed from: w, reason: collision with root package name */
    public int f128w;

    /* JADX INFO: renamed from: x, reason: collision with root package name */
    public float f129x;

    public c(VideoViewActivity videoViewActivity) {
        super(videoViewActivity);
        this.f108a = "VideoView";
        this.f113f = 0;
        this.f126u = 0;
        this.f124s = null;
        this.f117k = null;
        this.f123q = new a(this);
        this.f121o = new a(this);
        this.f110c = new a(this);
        this.f115h = new a(this);
        this.f109b = new a(this);
        b bVar = new b(this);
        this.f111d = videoViewActivity;
        this.f128w = 0;
        this.f127v = 0;
        getHolder().addCallback(bVar);
        getHolder().setType(3);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        this.f113f = 0;
        this.f126u = 0;
    }

    public final void a() {
        MediaController mediaController;
        if (this.f117k == null || (mediaController = this.j) == null) {
            return;
        }
        mediaController.setMediaPlayer(this);
        this.j.setAnchorView(getParent() instanceof View ? (View) getParent() : this);
        this.j.setEnabled(b());
    }

    public final boolean b() {
        int i8;
        return (this.f117k == null || (i8 = this.f113f) == -1 || i8 == 0 || i8 == 1) ? false : true;
    }

    public final void c() {
        a aVar = this.f115h;
        if (this.f116i == null || this.f124s == null) {
            return;
        }
        Intent intent = new Intent("com.android.music.musicservicecommand");
        intent.putExtra("command", "pause");
        this.f111d.sendBroadcast(intent);
        IjkMediaPlayer ijkMediaPlayer = this.f117k;
        if (ijkMediaPlayer != null) {
            ijkMediaPlayer.reset();
            this.f117k.release();
            this.f117k = null;
            this.f113f = 0;
        }
        try {
            IjkMediaPlayer ijkMediaPlayer2 = new IjkMediaPlayer();
            this.f117k = ijkMediaPlayer2;
            ijkMediaPlayer2.setOnPreparedListener(this.f121o);
            this.f117k.setOnVideoSizeChangedListener(this.f123q);
            this.f114g = -1;
            this.f117k.setOnCompletionListener(this.f110c);
            this.f117k.setOnErrorListener(aVar);
            this.f117k.setOnBufferingUpdateListener(this.f109b);
            this.f112e = 0;
            this.f117k.setDataSource(this.f116i);
            this.f117k.setDisplay(this.f124s);
            this.f117k.setAudioStreamType(3);
            IjkMediaPlayer ijkMediaPlayer3 = this.f117k;
            float f8 = this.f129x;
            ijkMediaPlayer3.setVolume(f8, f8);
            this.f117k.setScreenOnWhilePlaying(true);
            this.f117k.prepareAsync();
            this.f113f = 1;
            a();
        } catch (IOException | IllegalArgumentException e8) {
            Log.w(this.f108a, "Unable to open content: " + this.f116i, e8);
            this.f113f = -1;
            this.f126u = -1;
            aVar.onError(this.f117k, 1, 0);
        }
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final boolean canPause() {
        return false;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final boolean canSeekBackward() {
        return false;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final boolean canSeekForward() {
        return false;
    }

    public final void d(FileDescriptor fileDescriptor, int i8) {
        this.f116i = fileDescriptor;
        if (i8 >= 1000) {
            this.f129x = 1.0f;
        } else if (i8 <= 0) {
            this.f129x = 0.0f;
        } else {
            this.f129x = i8 / 1000.0f;
        }
        this.f122p = 0;
        c();
        requestLayout();
        invalidate();
    }

    public final void e() {
        if (this.j.isShowing()) {
            this.j.hide();
        } else {
            this.j.show();
        }
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public int getAudioSessionId() {
        return 0;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public int getBufferPercentage() {
        if (this.f117k != null) {
            return this.f112e;
        }
        return 0;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public int getCurrentPosition() {
        if (b()) {
            return (int) this.f117k.getCurrentPosition();
        }
        return 0;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public int getDuration() {
        if (!b()) {
            this.f114g = -1;
            return -1;
        }
        int i8 = this.f114g;
        if (i8 > 0) {
            return i8;
        }
        int duration = (int) this.f117k.getDuration();
        this.f114g = duration;
        return duration;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final boolean isPlaying() {
        return b() && this.f117k.isPlaying();
    }

    @Override // android.view.View, android.view.KeyEvent.Callback
    public final boolean onKeyDown(int i8, KeyEvent keyEvent) {
        boolean z = (i8 == 4 || i8 == 24 || i8 == 25 || i8 == 82 || i8 == 5 || i8 == 6) ? false : true;
        if (b() && z && this.j != null) {
            if (i8 == 79 || i8 == 85) {
                if (this.f117k.isPlaying()) {
                    pause();
                    this.j.show();
                    return true;
                }
                start();
                this.j.hide();
                return true;
            }
            if (i8 == 86 && this.f117k.isPlaying()) {
                pause();
                this.j.show();
            } else {
                e();
            }
        }
        return super.onKeyDown(i8, keyEvent);
    }

    @Override // android.view.SurfaceView, android.view.View
    public final void onMeasure(int i8, int i9) {
        int i10;
        int defaultSize = View.getDefaultSize(this.f128w, i8);
        int defaultSize2 = View.getDefaultSize(this.f127v, i9);
        int i11 = this.f128w;
        if (i11 > 0 && (i10 = this.f127v) > 0) {
            if (i11 * defaultSize2 > i10 * defaultSize) {
                defaultSize2 = (i10 * defaultSize) / i11;
            } else if (i11 * defaultSize2 < i10 * defaultSize) {
                defaultSize = (i11 * defaultSize2) / i10;
            }
        }
        setMeasuredDimension(defaultSize, defaultSize2);
    }

    @Override // android.view.View
    public final boolean onTouchEvent(MotionEvent motionEvent) {
        if (!b() || this.j == null) {
            return false;
        }
        e();
        return false;
    }

    @Override // android.view.View
    public final boolean onTrackballEvent(MotionEvent motionEvent) {
        if (!b() || this.j == null) {
            return false;
        }
        e();
        return false;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final void pause() {
        if (b() && this.f117k.isPlaying()) {
            this.f117k.pause();
            this.f113f = 4;
        }
        this.f126u = 4;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final void seekTo(int i8) {
        if (!b()) {
            this.f122p = i8;
        } else {
            this.f117k.seekTo(i8);
            this.f122p = 0;
        }
    }

    public void setMediaController(MediaController mediaController) {
        MediaController mediaController2 = this.j;
        if (mediaController2 != null) {
            mediaController2.hide();
        }
        this.j = mediaController;
        a();
    }

    public void setOnCompletionListener(IMediaPlayer.OnCompletionListener onCompletionListener) {
        this.f118l = onCompletionListener;
    }

    public void setOnErrorListener(IMediaPlayer.OnErrorListener onErrorListener) {
        this.f119m = onErrorListener;
    }

    public void setOnPreparedListener(IMediaPlayer.OnPreparedListener onPreparedListener) {
        this.f120n = onPreparedListener;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final void start() {
        if (b()) {
            this.f117k.start();
            this.f113f = 3;
        }
        this.f126u = 3;
    }
}
