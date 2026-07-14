package org.tvp.kirikiri2;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import org.cocos2dx.lib.Cocos2dxGLSurfaceView;

public final class h extends Cocos2dxGLSurfaceView {
    public h(Context context) { super(context); }

    private boolean usesCocosTouchPipeline() {
        try {
            KR2Activity activity = KR2Activity.getInstance();
            if (activity == null) activity = KR2Activity.GetInstance();
            if (activity == null) return false;
            String version = activity.getIntent() == null ? null
                    : activity.getIntent().getStringExtra("krEngineVersion");
            return "1.3.4".equals(version)
                    || activity.getClass().getName().endsWith("Kirikiroid134");
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override public final void deleteBackward() { KR2Activity.nativeDeleteBackward(); }
    @Override public final void insertText(String str) { KR2Activity.nativeInsertText(str); }

    @Override public final boolean onGenericMotionEvent(MotionEvent motionEvent) {
        if (motionEvent.getActionMasked() != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(motionEvent);
        KR2Activity.nativeMouseScrolled(-motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL));
        return true;
    }

    @Override public final boolean onHoverEvent(MotionEvent motionEvent) {
        int pointerCount = motionEvent.getPointerCount();
        float[] xs = new float[pointerCount];
        float[] ys = new float[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            xs[i] = motionEvent.getX(i);
            ys[i] = motionEvent.getY(i);
        }
        if (motionEvent.getActionMasked() != MotionEvent.ACTION_HOVER_MOVE) return true;
        KR2Activity.nativeHoverMoved(xs[0], ys[0]);
        return true;
    }

    @Override public final boolean onKeyDown(int keyCode, KeyEvent keyEvent) {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_MENU && keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    break;
                default:
                    return super.onKeyDown(keyCode, keyEvent);
            }
        }
        KR2Activity.nativeKeyAction(keyCode, true);
        return true;
    }

    @Override public final boolean onKeyUp(int keyCode, KeyEvent keyEvent) {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_MENU && keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    break;
                default:
                    return super.onKeyUp(keyCode, keyEvent);
            }
        }
        KR2Activity.nativeKeyAction(keyCode, false);
        return true;
    }

    @Override public final boolean onTouchEvent(MotionEvent motionEvent) {
        // libgame134's KR2Activity JNI path queues touch callbacks through an
        // internal dispatcher that is not drained by its Cocos lifecycle on
        // current Android.  Its standard Cocos JNI entry points are present and
        // dispatch correctly when reached through GLSurfaceView.queueEvent().
        if (usesCocosTouchPipeline()) return super.onTouchEvent(motionEvent);

        int pointerCount = motionEvent.getPointerCount();
        int[] ids = new int[pointerCount];
        float[] xs = new float[pointerCount];
        float[] ys = new float[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            ids[i] = motionEvent.getPointerId(i);
            xs[i] = motionEvent.getX(i);
            ys[i] = motionEvent.getY(i);
        }
        int action = motionEvent.getAction() & 255;
        if (action == MotionEvent.ACTION_DOWN) {
            KR2Activity.nativeTouchesBegin(motionEvent.getPointerId(0), xs[0], ys[0]);
        } else if (action == MotionEvent.ACTION_UP) {
            KR2Activity.nativeTouchesEnd(motionEvent.getPointerId(0), xs[0], ys[0]);
        } else if (action == MotionEvent.ACTION_MOVE) {
            KR2Activity.nativeTouchesMove(ids, xs, ys);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            KR2Activity.nativeTouchesCancel(ids, xs, ys);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            int actionIndex = motionEvent.getAction() >> 8;
            KR2Activity.nativeTouchesBegin(motionEvent.getPointerId(actionIndex), motionEvent.getX(actionIndex), motionEvent.getY(actionIndex));
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int actionIndex = motionEvent.getAction() >> 8;
            KR2Activity.nativeTouchesEnd(motionEvent.getPointerId(actionIndex), motionEvent.getX(actionIndex), motionEvent.getY(actionIndex));
        }
        return true;
    }
}
