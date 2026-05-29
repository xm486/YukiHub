package org.libsdl.app;

import android.os.Vibrator;
import android.view.InputDevice;
import java.util.ArrayList;
import java.util.Iterator;

/* JADX INFO: loaded from: classes.dex */
class SDLHapticHandler {
    private final ArrayList<SDLHaptic> mHaptics = new ArrayList<>();

    public static class SDLHaptic {
        public int device_id;
        public String name;
        public Vibrator vib;
    }

    public SDLHaptic getHaptic(int i8) {
        for (SDLHaptic sDLHaptic : this.mHaptics) {
            if (sDLHaptic.device_id == i8) {
                return sDLHaptic;
            }
        }
        return null;
    }

    public void pollHapticDevices() {
        boolean zHasVibrator;
        int[] deviceIds = InputDevice.getDeviceIds();
        int length = deviceIds.length;
        while (true) {
            length--;
            if (length <= -1) {
                break;
            }
            if (getHaptic(deviceIds[length]) == null) {
                InputDevice device = InputDevice.getDevice(deviceIds[length]);
                Vibrator vibrator = device.getVibrator();
                if (vibrator.hasVibrator()) {
                    SDLHaptic sDLHaptic = new SDLHaptic();
                    sDLHaptic.device_id = deviceIds[length];
                    sDLHaptic.name = device.getName();
                    sDLHaptic.vib = vibrator;
                    this.mHaptics.add(sDLHaptic);
                    SDLControllerManager.nativeAddHaptic(sDLHaptic.device_id, sDLHaptic.name);
                }
            }
        }
        Vibrator vibrator2 = (Vibrator) SDL.getContext().getSystemService("vibrator");
        if (vibrator2 != null) {
            zHasVibrator = vibrator2.hasVibrator();
            if (zHasVibrator && getHaptic(999999) == null) {
                SDLHaptic sDLHaptic2 = new SDLHaptic();
                sDLHaptic2.device_id = 999999;
                sDLHaptic2.name = "VIBRATOR_SERVICE";
                sDLHaptic2.vib = vibrator2;
                this.mHaptics.add(sDLHaptic2);
                SDLControllerManager.nativeAddHaptic(sDLHaptic2.device_id, sDLHaptic2.name);
            }
        } else {
            zHasVibrator = false;
        }
        Iterator<SDLHaptic> it = this.mHaptics.iterator();
        ArrayList arrayList = null;
        while (it.hasNext()) {
            int i8 = it.next().device_id;
            int i9 = 0;
            while (i9 < deviceIds.length && i8 != deviceIds[i9]) {
                i9++;
            }
            if (i8 != 999999 || !zHasVibrator) {
                if (i9 == deviceIds.length) {
                    if (arrayList == null) {
                        arrayList = new ArrayList();
                    }
                    arrayList.add(Integer.valueOf(i8));
                }
            }
        }
        if (arrayList != null) {
            Iterator it2 = arrayList.iterator();
            while (it2.hasNext()) {
                int iIntValue = ((Integer) it2.next()).intValue();
                SDLControllerManager.nativeRemoveHaptic(iIntValue);
                int i10 = 0;
                while (true) {
                    if (i10 >= this.mHaptics.size()) {
                        break;
                    }
                    if (this.mHaptics.get(i10).device_id == iIntValue) {
                        this.mHaptics.remove(i10);
                        break;
                    }
                    i10++;
                }
            }
        }
    }

    public void run(int i8, float f8, int i9) {
        SDLHaptic haptic = getHaptic(i8);
        if (haptic != null) {
            haptic.vib.vibrate(i9);
        }
    }

    public void stop(int i8) {
        SDLHaptic haptic = getHaptic(i8);
        if (haptic != null) {
            haptic.vib.cancel();
        }
    }
}
