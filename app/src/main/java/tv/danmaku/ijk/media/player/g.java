package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class g implements IMediaPlayer.OnBufferingUpdateListener {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ IMediaPlayer.OnBufferingUpdateListener f20731a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    public final /* synthetic */ MediaPlayerProxy f20732b;

    public g(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnBufferingUpdateListener onBufferingUpdateListener) {
        this.f20732b = mediaPlayerProxy;
        this.f20731a = onBufferingUpdateListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnBufferingUpdateListener
    public final void onBufferingUpdate(IMediaPlayer iMediaPlayer, int i8) {
        this.f20731a.onBufferingUpdate(this.f20732b, i8);
    }
}
