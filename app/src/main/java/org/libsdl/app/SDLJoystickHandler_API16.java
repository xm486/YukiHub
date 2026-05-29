package org.libsdl.app;

import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
class SDLJoystickHandler_API16 extends SDLJoystickHandler {
    private final ArrayList<SDLJoystick> mJoysticks = new ArrayList<>();

    public static class RangeComparator implements Comparator<InputDevice.MotionRange> {
        @Override // java.util.Comparator
        public int compare(InputDevice.MotionRange motionRange, InputDevice.MotionRange motionRange2) {
            int axis = motionRange.getAxis();
            int axis2 = motionRange2.getAxis();
            if (axis == 22) {
                axis = 23;
            } else if (axis == 23) {
                axis = 22;
            }
            if (axis2 == 22) {
                axis2 = 23;
            } else if (axis2 == 23) {
                axis2 = 22;
            }
            return axis - axis2;
        }
    }

    public static class SDLJoystick {
        public ArrayList<InputDevice.MotionRange> axes;
        public String desc;
        public int device_id;
        public ArrayList<InputDevice.MotionRange> hats;
        public String name;
    }

    public int getButtonMask(InputDevice inputDevice) {
        return -1;
    }

    public SDLJoystick getJoystick(int i8) {
        for (SDLJoystick sDLJoystick : this.mJoysticks) {
            if (sDLJoystick.device_id == i8) {
                return sDLJoystick;
            }
        }
        return null;
    }

    public String getJoystickDescriptor(InputDevice inputDevice) {
        String descriptor = inputDevice.getDescriptor();
        return (descriptor == null || descriptor.isEmpty()) ? inputDevice.getName() : descriptor;
    }

    public int getProductId(InputDevice inputDevice) {
        return 0;
    }

    public int getVendorId(InputDevice inputDevice) {
        return 0;
    }

    @Override // org.libsdl.app.SDLJoystickHandler
    public boolean handleMotionEvent(MotionEvent motionEvent) {
        SDLJoystick joystick;
        int actionIndex = motionEvent.getActionIndex();
        if (motionEvent.getActionMasked() == 2 && (joystick = getJoystick(motionEvent.getDeviceId())) != null) {
            for (int i8 = 0; i8 < joystick.axes.size(); i8++) {
                InputDevice.MotionRange motionRange = joystick.axes.get(i8);
                SDLControllerManager.onNativeJoy(joystick.device_id, i8, (((motionEvent.getAxisValue(motionRange.getAxis(), actionIndex) - motionRange.getMin()) / motionRange.getRange()) * 2.0f) - 1.0f);
            }
            for (int i9 = 0; i9 < joystick.hats.size() / 2; i9++) {
                int i10 = i9 * 2;
                SDLControllerManager.onNativeHat(joystick.device_id, i9, Math.round(motionEvent.getAxisValue(joystick.hats.get(i10).getAxis(), actionIndex)), Math.round(motionEvent.getAxisValue(joystick.hats.get(i10 + 1).getAxis(), actionIndex)));
            }
        }
        return true;
    }

    @Override // org.libsdl.app.SDLJoystickHandler
    public void pollInputDevices() {
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int i8 : deviceIds) {
            if (SDLControllerManager.isDeviceSDLJoystick(i8) && getJoystick(i8) == null) {
                InputDevice device = InputDevice.getDevice(i8);
                SDLJoystick sDLJoystick = new SDLJoystick();
                sDLJoystick.device_id = i8;
                sDLJoystick.name = device.getName();
                sDLJoystick.desc = getJoystickDescriptor(device);
                sDLJoystick.axes = new ArrayList<>();
                sDLJoystick.hats = new ArrayList<>();
                List<InputDevice.MotionRange> motionRanges = device.getMotionRanges();
                Collections.sort(motionRanges, new RangeComparator());
                for (InputDevice.MotionRange motionRange : motionRanges) {
                    if ((motionRange.getSource() & 16) != 0) {
                        if (motionRange.getAxis() == 15 || motionRange.getAxis() == 16) {
                            sDLJoystick.hats.add(motionRange);
                        } else {
                            sDLJoystick.axes.add(motionRange);
                        }
                    }
                }
                this.mJoysticks.add(sDLJoystick);
                SDLControllerManager.nativeAddJoystick(sDLJoystick.device_id, sDLJoystick.name, sDLJoystick.desc, getVendorId(device), getProductId(device), false, getButtonMask(device), sDLJoystick.axes.size(), sDLJoystick.hats.size() / 2, 0);
            }
        }
        Iterator<SDLJoystick> it = this.mJoysticks.iterator();
        ArrayList arrayList = null;
        while (it.hasNext()) {
            int i9 = it.next().device_id;
            int i10 = 0;
            while (i10 < deviceIds.length && i9 != deviceIds[i10]) {
                i10++;
            }
            if (i10 == deviceIds.length) {
                if (arrayList == null) {
                    arrayList = new ArrayList();
                }
                arrayList.add(Integer.valueOf(i9));
            }
        }
        if (arrayList != null) {
            Iterator it2 = arrayList.iterator();
            while (it2.hasNext()) {
                int iIntValue = ((Integer) it2.next()).intValue();
                SDLControllerManager.nativeRemoveJoystick(iIntValue);
                int i11 = 0;
                while (true) {
                    if (i11 >= this.mJoysticks.size()) {
                        break;
                    }
                    if (this.mJoysticks.get(i11).device_id == iIntValue) {
                        this.mJoysticks.remove(i11);
                        break;
                    }
                    i11++;
                }
            }
        }
    }
}
