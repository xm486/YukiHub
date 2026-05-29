package org.cocos2dx.lib;
import android.opengl.GLSurfaceView;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class Cocos2dxRenderer implements GLSurfaceView.Renderer {
    private static final long NANOSECONDSPERMICROSECOND = 1000000L;
    private static long sAnimationInterval = 16666666L;
    private long mLastTickInNanoSeconds;
    private boolean mNativeInitCompleted = false;
    private int mScreenWidth;
    private int mScreenHeight;

    private static native void nativeInit(int w, int h);
    private static native void nativeOnSurfaceChanged(int w, int h);
    private static native void nativeRender();
    private static native void nativeOnPause();
    private static native void nativeOnResume();
    private static native boolean nativeKeyEvent(int keyCode, boolean isPressed);
    private static native void nativeTouchesBegin(int id, float x, float y);
    private static native void nativeTouchesMove(int[] ids, float[] xs, float[] ys);
    private static native void nativeTouchesEnd(int id, float x, float y);
    private static native void nativeTouchesCancel(int[] ids, float[] xs, float[] ys);
    private static native void nativeInsertText(String text);
    private static native void nativeDeleteBackward();
    private static native String nativeGetContentText();

    public static void setAnimationInterval(float interval) {
        sAnimationInterval = (long) (interval * 1000000000.0f);
    }

    public void setScreenWidthAndHeight(int w, int h) {
        mScreenWidth = w;
        mScreenHeight = h;
    }

    public String getContentText() { return nativeGetContentText(); }
    public void handleInsertText(String text) { nativeInsertText(text); }
    public void handleDeleteBackward() { nativeDeleteBackward(); }
    public void handleKeyDown(int keyCode) { nativeKeyEvent(keyCode, true); }
    public void handleKeyUp(int keyCode) { nativeKeyEvent(keyCode, false); }
    public void handleActionDown(int id, float x, float y) { nativeTouchesBegin(id, x, y); }
    public void handleActionMove(int[] ids, float[] xs, float[] ys) { nativeTouchesMove(ids, xs, ys); }
    public void handleActionUp(int id, float x, float y) { nativeTouchesEnd(id, x, y); }
    public void handleActionCancel(int[] ids, float[] xs, float[] ys) { nativeTouchesCancel(ids, xs, ys); }

    public void handleOnPause() {
        if (mNativeInitCompleted) {
            try { Cocos2dxHelper.onEnterBackground(); } catch (Throwable ignored) { }
            nativeOnPause();
        }
    }

    public void handleOnResume() {
        Cocos2dxHelper.onEnterForeground();
        nativeOnResume();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        nativeInit(mScreenWidth, mScreenHeight);
        mLastTickInNanoSeconds = System.nanoTime();
        mNativeInitCompleted = true;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        nativeOnSurfaceChanged(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (sAnimationInterval > 16666666L) {
            long elapsed = System.nanoTime() - mLastTickInNanoSeconds;
            if (elapsed < sAnimationInterval) {
                try { Thread.sleep((sAnimationInterval - elapsed) / NANOSECONDSPERMICROSECOND); } catch (Throwable ignored) { }
            }
            mLastTickInNanoSeconds = System.nanoTime();
        }
        nativeRender();
    }
}