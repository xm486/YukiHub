package org.cocos2dx.lib;

import android.content.Context;

public class Cocos2dxBitmap {
    private static Context sContext;

    public static void setContext(Context context) {
        sContext = context;
    }

    public static Context getContext() {
        return sContext;
    }

    public static native void nativeInitBitmapDC(int width, int height, byte[] pixels);
}