package tv.danmaku.ijk.media.player;

import android.media.MediaDataSource;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

/* JADX INFO: loaded from: classes.dex */
public final class c extends MediaDataSource {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final IMediaDataSource f20725a;

    public c(IMediaDataSource iMediaDataSource) {
        this.f20725a = iMediaDataSource;
    }

    @Override // java.io.Closeable, java.lang.AutoCloseable
    public final void close() {
        this.f20725a.close();
    }

    @Override // android.media.MediaDataSource
    public final long getSize() {
        return this.f20725a.getSize();
    }

    @Override // android.media.MediaDataSource
    public final int readAt(long j, byte[] bArr, int i8, int i9) {
        return this.f20725a.readAt(j, bArr, i8, i9);
    }
}
