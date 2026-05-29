package tv.danmaku.ijk.media.player.misc;

/* JADX INFO: loaded from: classes.dex */
public interface IAndroidIO {
    int close();

    int open(String str);

    int read(byte[] bArr, int i8);

    long seek(long j, int i8);
}
