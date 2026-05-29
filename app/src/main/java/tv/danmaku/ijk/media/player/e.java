package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class e implements IMediaPlayer.OnPreparedListener {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ IMediaPlayer.OnPreparedListener f20727a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    public final /* synthetic */ MediaPlayerProxy f20728b;

    public e(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnPreparedListener onPreparedListener) {
        this.f20728b = mediaPlayerProxy;
        this.f20727a = onPreparedListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnPreparedListener
    public final void onPrepared(IMediaPlayer iMediaPlayer) {
        this.f20727a.onPrepared(this.f20728b);
    }
}
