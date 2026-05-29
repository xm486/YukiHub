package org.cocos2dx.lib;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;

public interface GameControllerDelegate {
    boolean dispatchKeyEvent(KeyEvent event);
    boolean dispatchGenericMotionEvent(MotionEvent event);
    void onCreate(Context context);
    void onResume();
    void onPause();
    void onDestroy();
}