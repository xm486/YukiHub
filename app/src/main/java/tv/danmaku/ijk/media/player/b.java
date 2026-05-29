package tv.danmaku.ijk.media.player;

import android.media.MediaPlayer;
import android.media.TimedText;
import java.lang.ref.WeakReference;

/* JADX INFO: loaded from: classes.dex */
public final class b implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnVideoSizeChangedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnInfoListener, MediaPlayer.OnTimedTextListener {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final WeakReference f20723a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    public final /* synthetic */ AndroidMediaPlayer f20724b;

    public b(AndroidMediaPlayer androidMediaPlayer, AndroidMediaPlayer androidMediaPlayer2) {
        this.f20724b = androidMediaPlayer;
        this.f20723a = new WeakReference(androidMediaPlayer2);
    }

    @Override // android.media.MediaPlayer.OnBufferingUpdateListener
    public final void onBufferingUpdate(MediaPlayer mediaPlayer, int i8) {
        if (((AndroidMediaPlayer) this.f20723a.get()) == null) {
            return;
        }
        this.f20724b.notifyOnBufferingUpdate(i8);
    }

    @Override // android.media.MediaPlayer.OnCompletionListener
    public final void onCompletion(MediaPlayer mediaPlayer) {
        if (((AndroidMediaPlayer) this.f20723a.get()) == null) {
            return;
        }
        this.f20724b.notifyOnCompletion();
    }

    @Override // android.media.MediaPlayer.OnErrorListener
    public final boolean onError(MediaPlayer mediaPlayer, int i8, int i9) {
        return ((AndroidMediaPlayer) this.f20723a.get()) != null && this.f20724b.notifyOnError(i8, i9);
    }

    @Override // android.media.MediaPlayer.OnInfoListener
    public final boolean onInfo(MediaPlayer mediaPlayer, int i8, int i9) {
        return ((AndroidMediaPlayer) this.f20723a.get()) != null && this.f20724b.notifyOnInfo(i8, i9);
    }

    @Override // android.media.MediaPlayer.OnPreparedListener
    public final void onPrepared(MediaPlayer mediaPlayer) {
        if (((AndroidMediaPlayer) this.f20723a.get()) == null) {
            return;
        }
        this.f20724b.notifyOnPrepared();
    }

    @Override // android.media.MediaPlayer.OnSeekCompleteListener
    public final void onSeekComplete(MediaPlayer mediaPlayer) {
        if (((AndroidMediaPlayer) this.f20723a.get()) == null) {
            return;
        }
        this.f20724b.notifyOnSeekComplete();
    }

    @Override // android.media.MediaPlayer.OnTimedTextListener
    public final void onTimedText(MediaPlayer mediaPlayer, TimedText timedText) {
        if (((AndroidMediaPlayer) this.f20723a.get()) == null) {
            return;
        }
        this.f20724b.notifyOnTimedText(timedText != null ? new IjkTimedText(timedText.getBounds(), timedText.getText()) : null);
    }

    @Override // android.media.MediaPlayer.OnVideoSizeChangedListener
    public final void onVideoSizeChanged(MediaPlayer mediaPlayer, int i8, int i9) {
        if (((AndroidMediaPlayer) this.f20723a.get()) == null) {
            return;
        }
        this.f20724b.notifyOnVideoSizeChanged(i8, i9, 1, 1);
    }
}
