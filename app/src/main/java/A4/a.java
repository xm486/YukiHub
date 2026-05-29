package A4;

import android.util.Log;
import android.widget.MediaController;
import tv.danmaku.ijk.media.player.IMediaPlayer;

public final class a implements IMediaPlayer.OnVideoSizeChangedListener, IMediaPlayer.OnPreparedListener, IMediaPlayer.OnCompletionListener, IMediaPlayer.OnErrorListener, IMediaPlayer.OnBufferingUpdateListener {
    public final c f106a;

    public a(c cVar) {
        this.f106a = cVar;
    }

    @Override
    public void onBufferingUpdate(IMediaPlayer iMediaPlayer, int percent) {
        this.f106a.f112e = percent;
    }

    @Override
    public void onCompletion(IMediaPlayer iMediaPlayer) {
        c cVar = this.f106a;
        cVar.f113f = 5;
        cVar.f126u = 5;
        MediaController mediaController = cVar.j;
        if (mediaController != null) mediaController.hide();
        IMediaPlayer.OnCompletionListener listener = cVar.f118l;
        if (listener != null) listener.onCompletion(cVar.f117k);
    }

    @Override
    public boolean onError(IMediaPlayer iMediaPlayer, int what, int extra) {
        c cVar = this.f106a;
        Log.d(cVar.f108a, "Error: " + what + "," + extra);
        cVar.f113f = -1;
        cVar.f126u = -1;
        MediaController mediaController = cVar.j;
        if (mediaController != null) mediaController.hide();
        IMediaPlayer.OnErrorListener listener = cVar.f119m;
        if (listener != null) listener.onError(cVar.f117k, what, extra);
        return true;
    }

    @Override
    public void onPrepared(IMediaPlayer iMediaPlayer) {
        c cVar = this.f106a;
        cVar.f113f = 2;
        IMediaPlayer.OnPreparedListener listener = cVar.f120n;
        if (listener != null) listener.onPrepared(cVar.f117k);
        MediaController mediaController = cVar.j;
        if (mediaController != null) mediaController.setEnabled(true);
        cVar.f128w = iMediaPlayer.getVideoWidth();
        cVar.f127v = iMediaPlayer.getVideoHeight();
        int seek = cVar.f122p;
        if (seek != 0) cVar.seekTo(seek);
        if (cVar.f128w == 0 || cVar.f127v == 0) {
            if (cVar.f126u == 3) cVar.start();
            return;
        }
        cVar.getHolder().setFixedSize(cVar.f128w, cVar.f127v);
        if (cVar.f125t == cVar.f128w && cVar.r == cVar.f127v) {
            if (cVar.f126u == 3) {
                cVar.start();
                if (cVar.j != null) cVar.j.show();
            } else if (!cVar.isPlaying() && (seek != 0 || cVar.getCurrentPosition() > 0) && cVar.j != null) {
                cVar.j.show(0);
            }
        }
    }

    @Override
    public void onVideoSizeChanged(IMediaPlayer iMediaPlayer, int width, int height, int sarNum, int sarDen) {
        c cVar = this.f106a;
        cVar.f128w = iMediaPlayer.getVideoWidth();
        cVar.f127v = iMediaPlayer.getVideoHeight();
        if (cVar.f128w != 0 && cVar.f127v != 0) cVar.getHolder().setFixedSize(cVar.f128w, cVar.f127v);
    }
}
