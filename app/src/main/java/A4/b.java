package A4;

import android.view.SurfaceHolder;
import android.widget.MediaController;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class b implements SurfaceHolder.Callback {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ c f107a;

    public b(c cVar) {
        this.f107a = cVar;
    }

    @Override // android.view.SurfaceHolder.Callback
    public final void surfaceChanged(SurfaceHolder surfaceHolder, int i8, int i9, int i10) {
        c cVar = this.f107a;
        cVar.f125t = i9;
        cVar.r = i10;
        boolean z = false;
        boolean z8 = cVar.f126u == 3;
        if (cVar.f128w == i9 && cVar.f127v == i10) {
            z = true;
        }
        if (cVar.f117k != null && z8 && z) {
            int i11 = cVar.f122p;
            if (i11 != 0) {
                cVar.seekTo(i11);
            }
            cVar.start();
            MediaController mediaController = cVar.j;
            if (mediaController != null) {
                if (mediaController.isShowing()) {
                    cVar.j.hide();
                }
                cVar.j.show();
            }
        }
    }

    @Override // android.view.SurfaceHolder.Callback
    public final void surfaceCreated(SurfaceHolder surfaceHolder) {
        c cVar = this.f107a;
        cVar.f124s = surfaceHolder;
        IjkMediaPlayer ijkMediaPlayer = cVar.f117k;
        if (ijkMediaPlayer == null || cVar.f113f != 6 || cVar.f126u != 7) {
            cVar.c();
            return;
        }
        ijkMediaPlayer.setDisplay(surfaceHolder);
        if (cVar.f124s == null && cVar.f113f == 6) {
            cVar.f126u = 7;
            return;
        }
        IjkMediaPlayer ijkMediaPlayer2 = cVar.f117k;
        if (ijkMediaPlayer2 == null || cVar.f113f != 6) {
            if (cVar.f113f == 8) {
                cVar.c();
            }
        } else {
            ijkMediaPlayer2.start();
            cVar.f113f = 0;
            cVar.f126u = 0;
        }
    }

    @Override // android.view.SurfaceHolder.Callback
    public final void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        IjkMediaPlayer ijkMediaPlayer;
        c cVar = this.f107a;
        cVar.f124s = null;
        MediaController mediaController = cVar.j;
        if (mediaController != null) {
            mediaController.hide();
        }
        if (cVar.f113f == 6 || (ijkMediaPlayer = cVar.f117k) == null) {
            return;
        }
        ijkMediaPlayer.reset();
        cVar.f117k.release();
        cVar.f117k = null;
        cVar.f113f = 0;
        cVar.f126u = 0;
    }
}
