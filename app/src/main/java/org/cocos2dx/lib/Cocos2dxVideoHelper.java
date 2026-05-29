package org.cocos2dx.lib;

import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

public class Cocos2dxVideoHelper {
    public static final int KeyEventBack = 1000;
    public static Handler mVideoHandler = new Handler(Looper.getMainLooper());
    private static int videoTag;
    public Cocos2dxVideoHelper(Cocos2dxActivity activity, FrameLayout layout) { }
    public static native void nativeExecuteVideoCallback(int tag, int event);
    public static int createVideoWidget() { return videoTag++; }
    public static void removeVideoWidget(int tag) { }
    public static void setVideoUrl(int tag, int source, String url) { }
    public static void setVideoRect(int tag, int x, int y, int w, int h) { }
    public static void startVideo(int tag) { }
    public static void pauseVideo(int tag) { }
    public static void resumeVideo(int tag) { }
    public static void stopVideo(int tag) { }
    public static void restartVideo(int tag) { }
    public static void seekVideoTo(int tag, int msec) { }
    public static void setVideoVisible(int tag, boolean visible) { }
    public static void setVideoKeepRatioEnabled(int tag, boolean enabled) { }
    public static void setFullScreenEnabled(int tag, boolean enabled, int width, int height) { }
}