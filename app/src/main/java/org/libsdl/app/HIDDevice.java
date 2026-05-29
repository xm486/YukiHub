package org.libsdl.app;

import android.hardware.usb.UsbDevice;

/* JADX INFO: loaded from: classes.dex */
interface HIDDevice {
    void close();

    UsbDevice getDevice();

    boolean getFeatureReport(byte[] bArr);

    int getId();

    String getManufacturerName();

    int getProductId();

    String getProductName();

    String getSerialNumber();

    int getVendorId();

    int getVersion();

    boolean open();

    int sendFeatureReport(byte[] bArr);

    int sendOutputReport(byte[] bArr);

    void setFrozen(boolean z);

    void shutdown();
}
