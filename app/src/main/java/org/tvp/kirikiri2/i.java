package org.tvp.kirikiri2;

import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;

public final class i extends BaseInputConnection {
    public i(View targetView, boolean fullEditor) { super(targetView, fullEditor); }
    @Override public boolean commitText(CharSequence text, int newCursorPosition) {
        KR2Activity.nativeCommitText(text.toString(), newCursorPosition);
        return super.commitText(text, newCursorPosition);
    }
    @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        return (beforeLength == 1 && afterLength == 0)
                ? super.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) && super.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                : super.deleteSurroundingText(beforeLength, afterLength);
    }
    @Override public boolean sendKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_DEL) KR2Activity.nativeKeyAction(keyCode, false);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.isPrintingKey()) {
                commitText(String.valueOf((char) event.getUnicodeChar()), 1);
                KR2Activity.nativeCharInput(keyCode);
            } else if (keyCode == KeyEvent.KEYCODE_DEL) {
                KR2Activity.nativeKeyAction(keyCode, true);
            }
            return true;
        }
        return super.sendKeyEvent(event);
    }
}