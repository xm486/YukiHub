package org.libsdl.app;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.text.InputType;
import android.view.inputmethod.InputConnection;

/* JADX INFO: loaded from: classes.dex */
class DummyEdit extends View implements View.OnKeyListener {
    InputConnection ic;

    public DummyEdit(Context context) {
        super(context);
        setFocusableInTouchMode(true);
        setFocusable(true);
        setOnKeyListener(this);
    }

    @Override // android.view.View
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override // android.view.View
    public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
        SDLInputConnection sDLInputConnection = new SDLInputConnection(this, true);
        this.ic = sDLInputConnection;
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        editorInfo.imeOptions = 301989888;
        return sDLInputConnection;
    }

    @Override // android.view.View.OnKeyListener
    public boolean onKey(View view, int i8, KeyEvent keyEvent) {
        return SDLActivity.handleKeyEvent(view, i8, keyEvent, this.ic);
    }

    @Override // android.view.View
    public boolean onKeyPreIme(int i8, KeyEvent keyEvent) {
        DummyEdit dummyEdit;
        if (keyEvent.getAction() == 1 && i8 == 4 && (dummyEdit = SDLActivity.mTextEdit) != null && dummyEdit.getVisibility() == 0) {
            SDLActivity.onNativeKeyboardFocusLost();
        }
        return super.onKeyPreIme(i8, keyEvent);
    }
}
