package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class h implements IMediaPlayer.OnSeekCompleteListener {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ IMediaPlayer.OnSeekCompleteListener f20733a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    public final /* synthetic */ MediaPlayerProxy f20734b;

    public h(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnSeekCompleteListener onSeekCompleteListener) {
        this.f20734b = mediaPlayerProxy;
        this.f20733a = onSeekCompleteListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnSeekCompleteListener
    public final void onSeekComplete(IMediaPlayer iMediaPlayer) {
        this.f20733a.onSeekComplete(this.f20734b);
    }
}
