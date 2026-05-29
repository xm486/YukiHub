package org.cocos2dx.lib;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

public class Cocos2dxTextInputWraper implements TextWatcher, TextView.OnEditorActionListener {
    private final Cocos2dxGLSurfaceView mView;
    private String mOriginText = "";
    public Cocos2dxTextInputWraper(Cocos2dxGLSurfaceView view) { mView = view; }
    public void setOriginText(String text) { mOriginText = text == null ? "" : text; }
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
    @Override public void afterTextChanged(Editable s) {
        String text = s == null ? "" : s.toString();
        if (!text.equals(mOriginText)) {
            if (text.length() > mOriginText.length()) mView.insertText(text.substring(mOriginText.length()));
            else mView.deleteBackward();
            mOriginText = text;
        }
    }
    @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEND) {
            Cocos2dxGLSurfaceView.closeIMEKeyboard();
            return true;
        }
        return false;
    }
}