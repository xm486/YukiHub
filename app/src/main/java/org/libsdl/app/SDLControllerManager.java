package org.libsdl.app;

import android.os.Build;
import android.view.InputDevice;
import android.view.MotionEvent;

/* JADX INFO: loaded from: classes.dex */
public class SDLControllerManager {
    private static final String TAG = "SDLControllerManager";
    protected static SDLHapticHandler mHapticHandler;
    protected static SDLJoystickHandler mJoystickHandler;

    public static boolean handleJoystickMotionEvent(MotionEvent motionEvent) {
        return mJoystickHandler.handleMotionEvent(motionEvent);
    }

    public static void hapticRun(int i8, float f8, int i9) {
        mHapticHandler.run(i8, f8, i9);
    }

    public static void hapticStop(int i8) {
        mHapticHandler.stop(i8);
    }

    public static void initialize() {
        if (mJoystickHandler == null) {
            mJoystickHandler = new SDLJoystickHandler_API19();
        }
        if (mHapticHandler == null) {
            if (Build.VERSION.SDK_INT >= 26) {
                mHapticHandler = new SDLHapticHandler_API26();
            } else {
                mHapticHandler = new SDLHapticHandler();
            }
        }
    }

    public static boolean isDeviceSDLJoystick(int i8) {
        InputDevice device = InputDevice.getDevice(i8);
        if (device == null || i8 < 0) {
            return false;
        }
        int sources = device.getSources();
        return (sources & 16) != 0 || (sources & 513) == 513 || (sources & 1025) == 1025;
    }

    public static native int nativeAddHaptic(int i8, String str);

    public static native int nativeAddJoystick(int i8, String str, String str2, int i9, int i10, boolean z, int i11, int i12, int i13, int i14);

    public static native int nativeRemoveHaptic(int i8);

    public static native int nativeRemoveJoystick(int i8);

    public static native int nativeSetupJNI();

    public static native void onNativeHat(int i8, int i9, int i10, int i11);

    public static native void onNativeJoy(int i8, int i9, float f8);

    public static native int onNativePadDown(int i8, int i9);

    public static native int onNativePadUp(int i8, int i9);

    public static void pollHapticDevices() {
        mHapticHandler.pollHapticDevices();
    }

    public static void pollInputDevices() {
        mJoystickHandler.pollInputDevices();
    }
}
