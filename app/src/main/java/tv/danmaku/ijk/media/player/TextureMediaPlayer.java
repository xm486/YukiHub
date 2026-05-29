package tv.danmaku.ijk.media.player;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceHolder;

/* JADX INFO: loaded from: classes.dex */
public class TextureMediaPlayer extends MediaPlayerProxy implements IMediaPlayer, ISurfaceTextureHolder {
    private SurfaceTexture mSurfaceTexture;
    private ISurfaceTextureHost mSurfaceTextureHost;

    public TextureMediaPlayer(IMediaPlayer iMediaPlayer) {
        super(iMediaPlayer);
    }

    @Override // tv.danmaku.ijk.media.player.ISurfaceTextureHolder
    public SurfaceTexture getSurfaceTexture() {
        return this.mSurfaceTexture;
    }

    @Override // tv.danmaku.ijk.media.player.MediaPlayerProxy, tv.danmaku.ijk.media.player.IMediaPlayer
    public void release() {
        super.release();
        releaseSurfaceTexture();
    }

    public void releaseSurfaceTexture() {
        SurfaceTexture surfaceTexture = this.mSurfaceTexture;
        if (surfaceTexture != null) {
            ISurfaceTextureHost iSurfaceTextureHost = this.mSurfaceTextureHost;
            if (iSurfaceTextureHost != null) {
                iSurfaceTextureHost.releaseSurfaceTexture(surfaceTexture);
            } else {
                surfaceTexture.release();
            }
            this.mSurfaceTexture = null;
        }
    }

    @Override // tv.danmaku.ijk.media.player.MediaPlayerProxy, tv.danmaku.ijk.media.player.IMediaPlayer
    public void reset() {
        super.reset();
        releaseSurfaceTexture();
    }

    @Override // tv.danmaku.ijk.media.player.MediaPlayerProxy, tv.danmaku.ijk.media.player.IMediaPlayer
    public void setDisplay(SurfaceHolder surfaceHolder) {
        if (this.mSurfaceTexture == null) {
            super.setDisplay(surfaceHolder);
        }
    }

    @Override // tv.danmaku.ijk.media.player.MediaPlayerProxy, tv.danmaku.ijk.media.player.IMediaPlayer
    public void setSurface(Surface surface) {
        if (this.mSurfaceTexture == null) {
            super.setSurface(surface);
        }
    }

    @Override // tv.danmaku.ijk.media.player.ISurfaceTextureHolder
    public void setSurfaceTexture(SurfaceTexture surfaceTexture) {
        if (this.mSurfaceTexture == surfaceTexture) {
            return;
        }
        releaseSurfaceTexture();
        this.mSurfaceTexture = surfaceTexture;
        if (surfaceTexture == null) {
            super.setSurface(null);
        } else {
            super.setSurface(new Surface(surfaceTexture));
        }
    }

    @Override // tv.danmaku.ijk.media.player.ISurfaceTextureHolder
    public void setSurfaceTextureHost(ISurfaceTextureHost iSurfaceTextureHost) {
        this.mSurfaceTextureHost = iSurfaceTextureHost;
    }
}
