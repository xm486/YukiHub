package org.libsdl.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import org.libsdl.app.SDLActivity;

/* JADX INFO: loaded from: classes.dex */
public class SDLSurface extends SurfaceView implements SurfaceHolder.Callback, View.OnKeyListener, View.OnTouchListener, SensorEventListener {
    protected Display mDisplay;
    protected float mHeight;
    public boolean mIsSurfaceReady;
    protected SensorManager mSensorManager;
    protected float mWidth;

    public SDLSurface(Context context) {
        super(context);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this);
        setOnTouchListener(this);
        this.mDisplay = ((WindowManager) context.getSystemService("window")).getDefaultDisplay();
        this.mSensorManager = (SensorManager) context.getSystemService("sensor");
        setOnGenericMotionListener(SDLActivity.getMotionListener());
        this.mWidth = 1.0f;
        this.mHeight = 1.0f;
        this.mIsSurfaceReady = false;
    }

    public void enableSensor(int i8, boolean z) {
        if (z) {
            SensorManager sensorManager = this.mSensorManager;
            sensorManager.registerListener(this, sensorManager.getDefaultSensor(i8), 1, (Handler) null);
        } else {
            SensorManager sensorManager2 = this.mSensorManager;
            sensorManager2.unregisterListener(this, sensorManager2.getDefaultSensor(i8));
        }
    }

    public Surface getNativeSurface() {
        return getHolder().getSurface();
    }

    public void handlePause() {
        enableSensor(1, false);
    }

    public void handleResume() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this);
        setOnTouchListener(this);
        enableSensor(1, true);
    }

    @Override // android.hardware.SensorEventListener
    public void onAccuracyChanged(Sensor sensor, int i8) {
    }

    @Override // android.view.View
    public boolean onCapturedPointerEvent(MotionEvent motionEvent) {
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 2 || actionMasked == 7) {
            SDLActivity.onNativeMouse(0, actionMasked, motionEvent.getX(0), motionEvent.getY(0), true);
            return true;
        }
        if (actionMasked == 8) {
            SDLActivity.onNativeMouse(0, actionMasked, motionEvent.getAxisValue(10, 0), motionEvent.getAxisValue(9, 0), false);
            return true;
        }
        if (actionMasked != 11 && actionMasked != 12) {
            return false;
        }
        SDLActivity.onNativeMouse(motionEvent.getButtonState(), actionMasked == 11 ? 0 : 1, motionEvent.getX(0), motionEvent.getY(0), true);
        return true;
    }

    @Override // android.view.View.OnKeyListener
    public boolean onKey(View view, int i8, KeyEvent keyEvent) {
        return SDLActivity.handleKeyEvent(view, i8, keyEvent, null);
    }

    @Override // android.hardware.SensorEventListener
    public void onSensorChanged(SensorEvent sensorEvent) {
        float f8;
        float f9;
        int i8 = 1;
        if (sensorEvent.sensor.getType() == 1) {
            int rotation = this.mDisplay.getRotation();
            if (rotation == 1) {
                float[] fArr = sensorEvent.values;
                float f10 = -fArr[1];
                f8 = fArr[0];
                f9 = f10;
            } else if (rotation == 2) {
                float[] fArr2 = sensorEvent.values;
                f9 = -fArr2[0];
                f8 = -fArr2[1];
                i8 = 4;
            } else if (rotation != 3) {
                float[] fArr3 = sensorEvent.values;
                f9 = fArr3[0];
                f8 = fArr3[1];
                i8 = 3;
            } else {
                float[] fArr4 = sensorEvent.values;
                float f11 = fArr4[1];
                f8 = -fArr4[0];
                f9 = f11;
                i8 = 2;
            }
            if (i8 != SDLActivity.mCurrentOrientation) {
                SDLActivity.mCurrentOrientation = i8;
                SDLActivity.onNativeOrientationChanged(i8);
            }
            SDLActivity.onNativeAccel((-f9) / 9.80665f, f8 / 9.80665f, sensorEvent.values[2] / 9.80665f);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:37:0x008e  */
    /* JADX WARN: Removed duplicated region for block: B:40:0x00ac  */
    /* JADX WARN: Removed duplicated region for block: B:42:0x00b0  */
    @Override // android.view.View.OnTouchListener
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public boolean onTouch(View view, MotionEvent motionEvent) {
        if (motionEvent == null) return true;
        int deviceId = motionEvent.getDeviceId();
        if (deviceId < 0) deviceId--;
        int pointerCount = motionEvent.getPointerCount();
        int actionMasked = motionEvent.getActionMasked();
        float width = this.mWidth > 0 ? this.mWidth : Math.max(1, getWidth());
        float height = this.mHeight > 0 ? this.mHeight : Math.max(1, getHeight());

        if (motionEvent.getSource() == 8194 || motionEvent.getSource() == 12290) {
            int buttonState = 1;
            try { buttonState = motionEvent.getButtonState(); } catch (Throwable ignored) { }
            SDLGenericMotionListener_API12 motionListener = SDLActivity.getMotionListener();
            SDLActivity.onNativeMouse(buttonState, actionMasked, motionListener.getEventX(motionEvent), motionListener.getEventY(motionEvent), motionListener.inRelativeMode());
            return true;
        }

        if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_UP
                || actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_POINTER_UP) {
            int pointerIndex = motionEvent.getActionIndex();
            if (pointerIndex < 0 || pointerIndex >= pointerCount) return true;
            sendNativeTouch(deviceId, motionEvent, pointerIndex, actionMasked, width, height);
        } else if (actionMasked == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < pointerCount; i++) {
                sendNativeTouch(deviceId, motionEvent, i, actionMasked, width, height);
            }
        } else if (actionMasked == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < pointerCount; i++) {
                sendNativeTouch(deviceId, motionEvent, i, MotionEvent.ACTION_UP, width, height);
            }
        }
        return true;
    }

    private void sendNativeTouch(int deviceId, MotionEvent event, int pointerIndex, int action, float width, float height) {
        try {
            if (event == null || pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;
            float safeWidth = width > 0 ? width : 1.0f;
            float safeHeight = height > 0 ? height : 1.0f;
            float pressure = event.getPressure(pointerIndex);
            if (pressure > 1.0f) pressure = 1.0f;
            if (pressure < 0.0f) pressure = 0.0f;
            SDLActivity.onNativeTouch(
                    deviceId,
                    event.getPointerId(pointerIndex),
                    action,
                    event.getX(pointerIndex) / safeWidth,
                    event.getY(pointerIndex) / safeHeight,
                    pressure
            );
        } catch (Throwable t) {
            Log.w("SDL", "drop invalid touch event: action=" + action + " index=" + pointerIndex + " count=" + (event == null ? 0 : event.getPointerCount()), t);
        }
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i8, int i9, int i10) {
        int i11 = i9;
        int i12 = i10;
        Log.v("SDL", "surfaceChanged()");
        if (SDLActivity.mSingleton == null) {
            return;
        }
        this.mWidth = i9;
        this.mHeight = i10;
        try {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            this.mDisplay.getRealMetrics(displayMetrics);
            i11 = displayMetrics.widthPixels;
            try {
                i12 = displayMetrics.heightPixels;
            } catch (Exception unused) {
                i12 = i10;
            }
        } catch (Exception unused2) {
            i11 = i9;
        }
        synchronized (SDLActivity.getContext()) {
            SDLActivity.getContext().notifyAll();
        }
        Log.v("SDL", "Window size: " + i9 + "x" + i10);
        Log.v("SDL", "Device size: " + i11 + "x" + i12);
        SDLActivity.nativeSetScreenResolution(i9, i10, i11, i12, this.mDisplay.getRefreshRate());
        SDLActivity.onNativeResize();
        int requestedOrientation = SDLActivity.mSingleton.getRequestedOrientation();
        boolean z = requestedOrientation == 1 || requestedOrientation == 7 ? this.mWidth > this.mHeight : !(!(requestedOrientation == 0 || requestedOrientation == 6) || this.mWidth >= this.mHeight);
        if (z) {
            if (((double) Math.max(this.mWidth, this.mHeight)) / ((double) Math.min(this.mWidth, this.mHeight)) < 1.2d) {
                Log.v("SDL", "Don't skip on such aspect-ratio. Could be a square resolution.");
                z = false;
            }
        }
        if (z && Build.VERSION.SDK_INT >= 24 && SDLActivity.mSingleton.isInMultiWindowMode()) {
            Log.v("SDL", "Don't skip in Multi-Window");
            z = false;
        }
        if (z) {
            Log.v("SDL", "Skip .. Surface is not ready.");
            this.mIsSurfaceReady = false;
        } else {
            SDLActivity.onNativeSurfaceChanged();
            this.mIsSurfaceReady = true;
            SDLActivity.mNextNativeState = SDLActivity.NativeState.RESUMED;
            SDLActivity.handleNativeState();
        }
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        Log.v("SDL", "surfaceCreated()");
        SDLActivity.onNativeSurfaceCreated();
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        Log.v("SDL", "surfaceDestroyed()");
        SDLActivity.mNextNativeState = SDLActivity.NativeState.PAUSED;
        SDLActivity.handleNativeState();
        this.mIsSurfaceReady = false;
        SDLActivity.onNativeSurfaceDestroyed();
    }
}
