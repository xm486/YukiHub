package E7;

import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.misc.IjkMediaFormat;

/* JADX INFO: loaded from: classes.dex */
public final class b extends d {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ IjkMediaFormat f1683a;

    public b(IjkMediaFormat ijkMediaFormat) {
        this.f1683a = ijkMediaFormat;
    }

    @Override // E7.d
    public final String a(IjkMediaFormat ijkMediaFormat) {
        return this.f1683a.mMediaFormat.getString(IjkMediaMeta.IJKM_KEY_CODEC_NAME);
    }
}
