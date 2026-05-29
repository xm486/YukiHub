package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class f implements IMediaPlayer.OnCompletionListener {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ IMediaPlayer.OnCompletionListener f20729a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    public final /* synthetic */ MediaPlayerProxy f20730b;

    public f(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnCompletionListener onCompletionListener) {
        this.f20730b = mediaPlayerProxy;
        this.f20729a = onCompletionListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnCompletionListener
    public final void onCompletion(IMediaPlayer iMediaPlayer) {
        this.f20729a.onCompletion(this.f20730b);
    }
}
