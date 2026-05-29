package org.libsdl.app;

import android.os.VibrationEffect;
import android.util.Log;
import org.libsdl.app.SDLHapticHandler;

/* JADX INFO: loaded from: classes.dex */
class SDLHapticHandler_API26 extends SDLHapticHandler {
    @Override // org.libsdl.app.SDLHapticHandler
    public void run(int i8, float f8, int i9) {
        SDLHapticHandler.SDLHaptic haptic = getHaptic(i8);
        if (haptic != null) {
            Log.d("SDL", "Rtest: Vibe with intensity " + f8 + " for " + i9);
            if (f8 == 0.0f) {
                stop(i8);
                return;
            }
            int iRound = Math.round(f8 * 255.0f);
            if (iRound > 255) {
                iRound = 255;
            }
            if (iRound < 1) {
                stop(i8);
                return;
            }
            try {
                haptic.vib.vibrate(VibrationEffect.createOneShot(i9, iRound));
            } catch (Exception unused) {
                haptic.vib.vibrate(i9);
            }
        }
    }
}
