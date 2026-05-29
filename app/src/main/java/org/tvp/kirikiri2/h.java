package org.tvp.kirikiri2;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import org.cocos2dx.lib.Cocos2dxGLSurfaceView;

public final class h extends Cocos2dxGLSurfaceView {
    public h(Context context) { super(context); }

    @Override public void deleteBackward() { KR2Activity.nativeDeleteBackward(); }
    @Override public void insertText(String text) { KR2Activity.nativeInsertText(text); }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(event);
        KR2Activity.nativeMouseScrolled(-event.getAxisValue(MotionEvent.AXIS_VSCROLL));
        return true;
    }

    @Override public boolean onHoverEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) KR2Activity.nativeHoverMoved(event.getX(0), event.getY(0));
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_MENU && keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                && keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT && keyCode != KeyEvent.KEYCODE_DPAD_CENTER) {
            return super.onKeyDown(keyCode, event);
        }
        KR2Activity.nativeKeyAction(keyCode, true);
        return true;
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_MENU && keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                && keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT && keyCode != KeyEvent.KEYCODE_DPAD_CENTER) {
            return super.onKeyUp(keyCode, event);
        }
        KR2Activity.nativeKeyAction(keyCode, false);
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int count = event.getPointerCount();
        int[] ids = new int[count];
        float[] xs = new float[count];
        float[] ys = new float[count];
        for (int i = 0; i < count; i++) { ids[i] = event.getPointerId(i); xs[i] = event.getX(i); ys[i] = event.getY(i); }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) KR2Activity.nativeTouchesBegin(event.getPointerId(0), xs[0], ys[0]);
        else if (action == MotionEvent.ACTION_UP) KR2Activity.nativeTouchesEnd(event.getPointerId(0), xs[0], ys[0]);
        else if (action == MotionEvent.ACTION_MOVE) KR2Activity.nativeTouchesMove(ids, xs, ys);
        else if (action == MotionEvent.ACTION_CANCEL) KR2Activity.nativeTouchesCancel(ids, xs, ys);
        else if (action == MotionEvent.ACTION_POINTER_DOWN) { int idx = event.getActionIndex(); KR2Activity.nativeTouchesBegin(event.getPointerId(idx), event.getX(idx), event.getY(idx)); }
        else if (action == MotionEvent.ACTION_POINTER_UP) { int idx = event.getActionIndex(); KR2Activity.nativeTouchesEnd(event.getPointerId(idx), event.getX(idx), event.getY(idx)); }
        return true;
    }
}