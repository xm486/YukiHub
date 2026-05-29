package org.libsdl.app;

import android.text.Editable;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;

/* JADX INFO: loaded from: classes.dex */
class SDLInputConnection extends BaseInputConnection {
    protected String mCommittedText;
    protected EditText mEditText;

    public SDLInputConnection(View view, boolean z) {
        super(view, z);
        this.mCommittedText = "";
        this.mEditText = new EditText(SDL.getContext());
    }

    public static native void nativeCommitText(String str, int i8);

    public static native void nativeGenerateScancodeForUnichar(char c8);

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean commitText(CharSequence charSequence, int i8) {
        if (!super.commitText(charSequence, i8)) {
            return false;
        }
        updateText();
        return true;
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean deleteSurroundingText(int i8, int i9) {
        if (!super.deleteSurroundingText(i8, i9)) {
            return false;
        }
        updateText();
        return true;
    }

    @Override // android.view.inputmethod.BaseInputConnection
    public Editable getEditable() {
        return this.mEditText.getEditableText();
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean sendKeyEvent(KeyEvent keyEvent) {
        if (keyEvent.getKeyCode() == 66 && SDLActivity.onNativeSoftReturnKey()) {
            return true;
        }
        return super.sendKeyEvent(keyEvent);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean setComposingText(CharSequence charSequence, int i8) {
        if (!super.setComposingText(charSequence, i8)) {
            return false;
        }
        updateText();
        return true;
    }

    public void updateText() {
        Editable editable = getEditable();
        if (editable == null) {
            return;
        }
        String string = editable.toString();
        int iMin = Math.min(string.length(), this.mCommittedText.length());
        int iCharCount = 0;
        while (iCharCount < iMin) {
            int iCodePointAt = this.mCommittedText.codePointAt(iCharCount);
            if (iCodePointAt != string.codePointAt(iCharCount)) {
                break;
            } else {
                iCharCount += Character.charCount(iCodePointAt);
            }
        }
        int iCharCount2 = iCharCount;
        while (iCharCount2 < this.mCommittedText.length()) {
            int iCodePointAt2 = this.mCommittedText.codePointAt(iCharCount2);
            nativeGenerateScancodeForUnichar('\b');
            iCharCount2 += Character.charCount(iCodePointAt2);
        }
        if (iCharCount < string.length()) {
            String string2 = string.subSequence(iCharCount, string.length()).toString();
            int iCharCount3 = 0;
            while (iCharCount3 < string2.length()) {
                int iCodePointAt3 = string2.codePointAt(iCharCount3);
                if (iCodePointAt3 == 10 && SDLActivity.onNativeSoftReturnKey()) {
                    return;
                }
                if (iCodePointAt3 < 128) {
                    nativeGenerateScancodeForUnichar((char) iCodePointAt3);
                }
                iCharCount3 += Character.charCount(iCodePointAt3);
            }
            nativeCommitText(string2, 0);
        }
        this.mCommittedText = string;
    }
}
