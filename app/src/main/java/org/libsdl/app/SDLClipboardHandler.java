package org.libsdl.app;

import android.content.ClipData;
import android.content.ClipboardManager;

/* JADX INFO: loaded from: classes.dex */
class SDLClipboardHandler implements ClipboardManager.OnPrimaryClipChangedListener {
    protected ClipboardManager mClipMgr;

    public SDLClipboardHandler() {
        ClipboardManager clipboardManager = (ClipboardManager) SDL.getContext().getSystemService("clipboard");
        this.mClipMgr = clipboardManager;
        clipboardManager.addPrimaryClipChangedListener(this);
    }

    public String clipboardGetText() {
        ClipData.Item itemAt;
        CharSequence text;
        ClipData primaryClip = this.mClipMgr.getPrimaryClip();
        if (primaryClip == null || (itemAt = primaryClip.getItemAt(0)) == null || (text = itemAt.getText()) == null) {
            return null;
        }
        return text.toString();
    }

    public boolean clipboardHasText() {
        return this.mClipMgr.hasPrimaryClip();
    }

    public void clipboardSetText(String str) {
        this.mClipMgr.removePrimaryClipChangedListener(this);
        this.mClipMgr.setPrimaryClip(ClipData.newPlainText(null, str));
        this.mClipMgr.addPrimaryClipChangedListener(this);
    }

    @Override // android.content.ClipboardManager.OnPrimaryClipChangedListener
    public void onPrimaryClipChanged() {
        SDLActivity.onNativeClipboardChanged();
    }
}
