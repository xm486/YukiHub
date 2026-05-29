package tv.danmaku.ijk.media.player.misc;

/* JADX INFO: loaded from: classes.dex */
public interface IMediaDataSource {
    void close();

    long getSize();

    int readAt(long j, byte[] bArr, int i8, int i9);
}
