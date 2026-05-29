package org.libsdl.app;

import android.view.MotionEvent;
import android.view.View;

/* JADX INFO: loaded from: classes.dex */
class SDLGenericMotionListener_API24 extends SDLGenericMotionListener_API12 {
    private boolean mRelativeModeEnabled;

    @Override // org.libsdl.app.SDLGenericMotionListener_API12
    public float getEventX(MotionEvent motionEvent) {
        return this.mRelativeModeEnabled ? motionEvent.getAxisValue(27) : motionEvent.getX(0);
    }

    @Override // org.libsdl.app.SDLGenericMotionListener_API12
    public float getEventY(MotionEvent motionEvent) {
        return this.mRelativeModeEnabled ? motionEvent.getAxisValue(28) : motionEvent.getY(0);
    }

    @Override // org.libsdl.app.SDLGenericMotionListener_API12
    public boolean inRelativeMode() {
        return this.mRelativeModeEnabled;
    }

    @Override // org.libsdl.app.SDLGenericMotionListener_API12, android.view.View.OnGenericMotionListener
    public boolean onGenericMotion(View view, MotionEvent motionEvent) {
        int actionMasked;
        if (!this.mRelativeModeEnabled || motionEvent.getSource() != 8194 || (actionMasked = motionEvent.getActionMasked()) != 7) {
            return super.onGenericMotion(view, motionEvent);
        }
        SDLActivity.onNativeMouse(0, actionMasked, motionEvent.getAxisValue(27), motionEvent.getAxisValue(28), true);
        return true;
    }

    @Override // org.libsdl.app.SDLGenericMotionListener_API12
    public boolean setRelativeMouseEnabled(boolean z) {
        this.mRelativeModeEnabled = z;
        return true;
    }

    @Override // org.libsdl.app.SDLGenericMotionListener_API12
    public boolean supportsRelativeMouse() {
        return true;
    }
}
