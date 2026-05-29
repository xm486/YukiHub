package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class l implements IMediaPlayer.OnTimedTextListener {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ IMediaPlayer.OnTimedTextListener f20741a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    public final /* synthetic */ MediaPlayerProxy f20742b;

    public l(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnTimedTextListener onTimedTextListener) {
        this.f20742b = mediaPlayerProxy;
        this.f20741a = onTimedTextListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnTimedTextListener
    public final void onTimedText(IMediaPlayer iMediaPlayer, IjkTimedText ijkTimedText) {
        this.f20741a.onTimedText(this.f20742b, ijkTimedText);
    }
}
