package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class j implements IMediaPlayer.OnErrorListener {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ IMediaPlayer.OnErrorListener f20737a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    public final /* synthetic */ MediaPlayerProxy f20738b;

    public j(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnErrorListener onErrorListener) {
        this.f20738b = mediaPlayerProxy;
        this.f20737a = onErrorListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnErrorListener
    public final boolean onError(IMediaPlayer iMediaPlayer, int i8, int i9) {
        return this.f20737a.onError(this.f20738b, i8, i9);
    }
}
