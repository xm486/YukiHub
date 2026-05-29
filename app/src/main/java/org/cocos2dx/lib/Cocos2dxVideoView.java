package org.cocos2dx.lib;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceView;
import android.widget.FrameLayout;

public class Cocos2dxVideoView extends SurfaceView {
    public static final int EventPlaying = 0;
    public static final int EventPaused = 1;
    public static final int EventStopped = 2;
    public static final int EventCompleted = 3;
    public Cocos2dxVideoView(Context context, int tag) { super(context); }
    public void setVideoURL(String url) { }
    public void setVideoURI(Uri uri) { }
    public void setVideoFileName(String path) { }
    public void setVideoRect(int left, int top, int maxWidth, int maxHeight) { setLayoutParams(new FrameLayout.LayoutParams(maxWidth, maxHeight)); }
    public void start() { }
    public void stop() { }
    public void pause() { }
    public void resume() { }
    public void restart() { }
    public void seekTo(int msec) { }
    public void setVisible(boolean visible) { setVisibility(visible ? VISIBLE : GONE); }
    public void setFullScreenEnabled(boolean enabled, int width, int height) { }
    public void setKeepRatio(boolean enabled) { }
}