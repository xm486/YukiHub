package org.libsdl.app;

import android.view.InputDevice;

/* JADX INFO: loaded from: classes.dex */
class SDLJoystickHandler_API19 extends SDLJoystickHandler_API16 {
    @Override // org.libsdl.app.SDLJoystickHandler_API16
    public int getButtonMask(InputDevice inputDevice) {
        int[] iArr = {96, 97, 99, 100, 4, 82, 110, 108, 106, 107, 102, 103, 19, 20, 21, 22, 109, 23, 104, 105, 98, 122, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203};
        int[] iArr2 = {1, 2, 4, 8, 16, 64, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 16, 1, 32768, 65536, 131072, 262144, 1048576, 2097152, 4194304, 8388608, 16777216, 33554432, 67108864, 134217728, 268435456, 536870912, 1073741824, Integer.MIN_VALUE, -1, -1, -1, -1};
        boolean[] zArrHasKeys = inputDevice.hasKeys(iArr);
        int i8 = 0;
        for (int i9 = 0; i9 < 38; i9++) {
            if (zArrHasKeys[i9]) {
                i8 |= iArr2[i9];
            }
        }
        return i8;
    }

    @Override // org.libsdl.app.SDLJoystickHandler_API16
    public int getProductId(InputDevice inputDevice) {
        return inputDevice.getProductId();
    }

    @Override // org.libsdl.app.SDLJoystickHandler_API16
    public int getVendorId(InputDevice inputDevice) {
        return inputDevice.getVendorId();
    }
}
