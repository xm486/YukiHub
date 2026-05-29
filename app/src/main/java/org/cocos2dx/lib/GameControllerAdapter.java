package org.cocos2dx.lib;

public class GameControllerAdapter {
    public static native void nativeControllerConnected(int controller, String name);
    public static native void nativeControllerDisconnected(int controller);
    public static native void nativeControllerButtonEvent(int controller, int button, boolean pressed, float value, boolean analog);
    public static native void nativeControllerAxisEvent(int controller, int axis, float value, boolean analog);
}