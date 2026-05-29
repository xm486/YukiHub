package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class i implements IMediaPlayer.OnVideoSizeChangedListener {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ IMediaPlayer.OnVideoSizeChangedListener f20735a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    public final /* synthetic */ MediaPlayerProxy f20736b;

    public i(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnVideoSizeChangedListener onVideoSizeChangedListener) {
        this.f20736b = mediaPlayerProxy;
        this.f20735a = onVideoSizeChangedListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnVideoSizeChangedListener
    public final void onVideoSizeChanged(IMediaPlayer iMediaPlayer, int i8, int i9, int i10, int i11) {
        this.f20735a.onVideoSizeChanged(this.f20736b, i8, i9, i10, i11);
    }
}
