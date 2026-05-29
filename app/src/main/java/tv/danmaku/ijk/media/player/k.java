package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class k implements IMediaPlayer.OnInfoListener {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ IMediaPlayer.OnInfoListener f20739a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    public final /* synthetic */ MediaPlayerProxy f20740b;

    public k(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnInfoListener onInfoListener) {
        this.f20740b = mediaPlayerProxy;
        this.f20739a = onInfoListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnInfoListener
    public final boolean onInfo(IMediaPlayer iMediaPlayer, int i8, int i9) {
        return this.f20739a.onInfo(this.f20740b, i8, i9);
    }
}
