package org.cocos2dx.lib;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

public abstract class Cocos2dxActivity extends Activity {
    private static final String TAG = "Cocos2dxActivity";
    private static Cocos2dxActivity sContext;
    public ResizeLayout mFrameLayout;
    private Cocos2dxGLSurfaceView mGLSurfaceView;
    public int[] mGLContextAttrs;
    private boolean hasFocus;
    private Cocos2dxVideoHelper mVideoHelper;
    private Cocos2dxWebViewHelper mWebViewHelper;
    private Cocos2dxEditBoxHelper mEditBoxHelper;

    public static Context getContext() { return sContext; }
    public Cocos2dxGLSurfaceView getGLSurfaceView() { return mGLSurfaceView; }

    private static native int[] getGLContextAttrs();

    public void onLoadNativeLibraries() {
        try {
            System.loadLibrary(getPackageManager().getApplicationInfo(getPackageName(), 128).metaData.getString("android.app.lib_name"));
        } catch (Throwable t) {
            Log.e(TAG, "onLoadNativeLibraries failed", t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        onLoadNativeLibraries();
        sContext = this;
        Cocos2dxHelper.init(this);
        mGLContextAttrs = getGLContextAttrs();
        init();
        if (mVideoHelper == null) mVideoHelper = new Cocos2dxVideoHelper(this, mFrameLayout);
        if (mWebViewHelper == null) mWebViewHelper = new Cocos2dxWebViewHelper(mFrameLayout);
        if (mEditBoxHelper == null) mEditBoxHelper = new Cocos2dxEditBoxHelper(mFrameLayout);
        getWindow().setSoftInputMode(32);
    }

    public void init() {
        mFrameLayout = new ResizeLayout(this);
        mFrameLayout.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        Cocos2dxEditBox editBox = new Cocos2dxEditBox(this);
        editBox.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        mFrameLayout.addView(editBox);
        mGLSurfaceView = onCreateView();
        mFrameLayout.addView(mGLSurfaceView);
        Cocos2dxRenderer renderer = new Cocos2dxRenderer();
        mGLSurfaceView.setCocos2dxRenderer(renderer);
        mGLSurfaceView.setCocos2dxEditText(editBox);
        setContentView(mFrameLayout);
    }

    public Cocos2dxGLSurfaceView onCreateView() {
        Cocos2dxGLSurfaceView gl = new Cocos2dxGLSurfaceView(this);
        if (mGLContextAttrs != null && mGLContextAttrs.length > 3 && mGLContextAttrs[3] > 0) {
            gl.getHolder().setFormat(-3);
        }
        if (mGLContextAttrs != null) gl.setEGLConfigChooser(new Cocos2dxEGLConfigChooser(mGLContextAttrs));
        return gl;
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause()");
        super.onPause();
        Cocos2dxHelper.onPause();
        if (mGLSurfaceView != null) mGLSurfaceView.onPause();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();
        resumeIfHasFocus();
    }

    private void resumeIfHasFocus() {
        if (hasFocus) {
            Cocos2dxHelper.onResume();
            if (mGLSurfaceView != null) mGLSurfaceView.onResume();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        this.hasFocus = hasFocus;
        resumeIfHasFocus();
    }

    public void setKeepScreenOn(final boolean keep) {
        runOnUiThread(() -> { if (mGLSurfaceView != null) mGLSurfaceView.setKeepScreenOn(keep); });
    }

    public void runOnGLThread(Runnable runnable) {
        if (mGLSurfaceView != null) mGLSurfaceView.queueEvent(runnable);
    }

    public static class Cocos2dxEGLConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private final int[] configAttribs;
        public Cocos2dxEGLConfigChooser(int[] attrs) { this.configAttribs = attrs; }
        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[] attribList = new int[]{
                    0x3024, configAttribs.length > 0 ? configAttribs[0] : 8,
                    0x3023, configAttribs.length > 1 ? configAttribs[1] : 8,
                    0x3022, configAttribs.length > 2 ? configAttribs[2] : 8,
                    0x3021, configAttribs.length > 3 ? configAttribs[3] : 8,
                    0x3025, configAttribs.length > 4 ? configAttribs[4] : 16,
                    0x3026, configAttribs.length > 5 ? configAttribs[5] : 0,
                    0x3040, 4,
                    0x3038
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (egl.eglChooseConfig(display, attribList, configs, 1, num) && num[0] > 0) return configs[0];
            return null;
        }
    }
}