package org.cocos2dx.lib;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;

public class Cocos2dxGLSurfaceView extends GLSurfaceView {
    private static Cocos2dxGLSurfaceView sInstance;
    private static Cocos2dxTextInputWraper sCocos2dxTextInputWraper;
    private Cocos2dxRenderer mCocos2dxRenderer;
    private Cocos2dxEditBox mCocos2dxEditText;
    private boolean mSoftKeyboardShown = false;

    public Cocos2dxGLSurfaceView(Context context) {
        super(context);
        initView();
    }

    public static Cocos2dxGLSurfaceView getInstance() { return sInstance; }

    public static void closeIMEKeyboard() {
        if (sInstance != null && sInstance.mCocos2dxEditText != null) {
            try {
                sInstance.mCocos2dxEditText.removeTextChangedListener(sCocos2dxTextInputWraper);
                ((InputMethodManager) sInstance.getContext().getSystemService("input_method")).hideSoftInputFromWindow(sInstance.mCocos2dxEditText.getWindowToken(), 0);
                sInstance.requestFocus();
            } catch (Throwable ignored) { }
        }
    }

    public static void openIMEKeyboard() {
        if (sInstance != null && sInstance.mCocos2dxEditText != null) {
            try {
                sInstance.mCocos2dxEditText.requestFocus();
                sInstance.mCocos2dxEditText.removeTextChangedListener(sCocos2dxTextInputWraper);
                sInstance.mCocos2dxEditText.setText("");
                String content = sInstance.mCocos2dxRenderer != null ? sInstance.mCocos2dxRenderer.getContentText() : "";
                sInstance.mCocos2dxEditText.append(content);
                sCocos2dxTextInputWraper.setOriginText(content);
                sInstance.mCocos2dxEditText.addTextChangedListener(sCocos2dxTextInputWraper);
                ((InputMethodManager) sInstance.getContext().getSystemService("input_method")).showSoftInput(sInstance.mCocos2dxEditText, 0);
            } catch (Throwable ignored) { }
        }
    }

    private void initView() {
        setEGLContextClientVersion(2);
        setFocusableInTouchMode(true);
        sInstance = this;
        sCocos2dxTextInputWraper = new Cocos2dxTextInputWraper(this);
    }

    public void setCocos2dxEditText(Cocos2dxEditBox editText) {
        mCocos2dxEditText = editText;
        if (mCocos2dxEditText != null && sCocos2dxTextInputWraper != null) {
            mCocos2dxEditText.setOnEditorActionListener(sCocos2dxTextInputWraper);
        }
        requestFocus();
    }

    public void setCocos2dxRenderer(Cocos2dxRenderer renderer) {
        mCocos2dxRenderer = renderer;
        setRenderer(renderer);
    }

    public void setSoftKeyboardShown(boolean shown) { mSoftKeyboardShown = shown; }

    public void insertText(final String text) {
        if (mCocos2dxRenderer != null) queueEvent(() -> mCocos2dxRenderer.handleInsertText(text));
    }

    public void deleteBackward() {
        if (mCocos2dxRenderer != null) queueEvent(() -> mCocos2dxRenderer.handleDeleteBackward());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!isInEditMode() && mCocos2dxRenderer != null) {
            mCocos2dxRenderer.setScreenWidthAndHeight(w, h);
        }
    }

    @Override
    public void onPause() {
        if (mCocos2dxRenderer != null) queueEvent(() -> mCocos2dxRenderer.handleOnPause());
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onResume() {
        super.onResume();
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        if (mCocos2dxRenderer != null) queueEvent(() -> mCocos2dxRenderer.handleOnResume());
    }

    @Override
    public boolean onKeyDown(final int keyCode, KeyEvent event) {
        if (!isCocosHandledKey(keyCode)) return super.onKeyDown(keyCode, event);
        if (mCocos2dxRenderer != null) {
            queueEvent(() -> mCocos2dxRenderer.handleKeyDown(keyCode));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(final int keyCode, KeyEvent event) {
        if (!isCocosHandledKey(keyCode)) return super.onKeyUp(keyCode, event);
        if (mCocos2dxRenderer != null) {
            queueEvent(() -> mCocos2dxRenderer.handleKeyUp(keyCode));
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean isCocosHandledKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mCocos2dxRenderer == null) return true;
        int count = event.getPointerCount();
        final int[] ids = new int[count];
        final float[] xs = new float[count];
        final float[] ys = new float[count];
        if (mSoftKeyboardShown) {
            try {
                ((InputMethodManager) getContext().getSystemService("input_method"))
                        .hideSoftInputFromWindow(((Activity) getContext()).getCurrentFocus().getWindowToken(), 0);
            } catch (Throwable ignored) { }
            requestFocus();
            mSoftKeyboardShown = false;
        }
        for (int i = 0; i < count; i++) {
            ids[i] = event.getPointerId(i);
            xs[i] = event.getX(i);
            ys[i] = event.getY(i);
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            final int id = event.getPointerId(0);
            final float x = xs[0], y = ys[0];
            queueEvent(() -> mCocos2dxRenderer.handleActionDown(id, x, y));
        } else if (action == MotionEvent.ACTION_UP) {
            final int id = event.getPointerId(0);
            final float x = xs[0], y = ys[0];
            queueEvent(() -> mCocos2dxRenderer.handleActionUp(id, x, y));
        } else if (action == MotionEvent.ACTION_MOVE) {
            queueEvent(() -> mCocos2dxRenderer.handleActionMove(ids, xs, ys));
        } else if (action == MotionEvent.ACTION_CANCEL) {
            queueEvent(() -> mCocos2dxRenderer.handleActionCancel(ids, xs, ys));
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            final int id = event.getPointerId(index);
            final float x = event.getX(index), y = event.getY(index);
            queueEvent(() -> mCocos2dxRenderer.handleActionDown(id, x, y));
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int index = event.getActionIndex();
            final int id = event.getPointerId(index);
            final float x = event.getX(index), y = event.getY(index);
            queueEvent(() -> mCocos2dxRenderer.handleActionUp(id, x, y));
        }
        return true;
    }
}