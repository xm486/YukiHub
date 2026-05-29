package org.cocos2dx.lib;

public class Cocos2dxAccelerometer {
    public static native void onSensorChanged(float x, float y, float z, long timestamp);
}