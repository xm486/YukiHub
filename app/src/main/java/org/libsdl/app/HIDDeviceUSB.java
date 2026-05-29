package org.libsdl.app;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;
import java.util.Arrays;

/* JADX INFO: loaded from: classes.dex */
class HIDDeviceUSB implements HIDDevice {
    private static final String TAG = "hidapi";
    protected UsbDeviceConnection mConnection;
    protected UsbDevice mDevice;
    protected int mDeviceId;
    protected boolean mFrozen;
    protected UsbEndpoint mInputEndpoint;
    protected InputThread mInputThread;
    protected int mInterface;
    protected int mInterfaceIndex;
    protected HIDDeviceManager mManager;
    protected UsbEndpoint mOutputEndpoint;
    protected boolean mRunning = false;

    public class InputThread extends Thread {
        public InputThread() {
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            int maxPacketSize = HIDDeviceUSB.this.mInputEndpoint.getMaxPacketSize();
            byte[] bArr = new byte[maxPacketSize];
            while (true) {
                HIDDeviceUSB hIDDeviceUSB = HIDDeviceUSB.this;
                if (!hIDDeviceUSB.mRunning) {
                    return;
                }
                try {
                    int iBulkTransfer = hIDDeviceUSB.mConnection.bulkTransfer(hIDDeviceUSB.mInputEndpoint, bArr, maxPacketSize, 1000);
                    if (iBulkTransfer > 0) {
                        byte[] bArrCopyOfRange = iBulkTransfer == maxPacketSize ? bArr : Arrays.copyOfRange(bArr, 0, iBulkTransfer);
                        HIDDeviceUSB hIDDeviceUSB2 = HIDDeviceUSB.this;
                        if (!hIDDeviceUSB2.mFrozen) {
                            hIDDeviceUSB2.mManager.HIDDeviceInputReport(hIDDeviceUSB2.mDeviceId, bArrCopyOfRange);
                        }
                    }
                } catch (Exception e8) {
                    Log.v(HIDDeviceUSB.TAG, "Exception in UsbDeviceConnection bulktransfer: " + e8);
                    return;
                }
            }
        }
    }

    public HIDDeviceUSB(HIDDeviceManager hIDDeviceManager, UsbDevice usbDevice, int i8) {
        this.mManager = hIDDeviceManager;
        this.mDevice = usbDevice;
        this.mInterfaceIndex = i8;
        this.mInterface = usbDevice.getInterface(i8).getId();
        this.mDeviceId = hIDDeviceManager.getDeviceIDForIdentifier(getIdentifier());
    }

    @Override // org.libsdl.app.HIDDevice
    public void close() {
        this.mRunning = false;
        if (this.mInputThread != null) {
            while (this.mInputThread.isAlive()) {
                this.mInputThread.interrupt();
                try {
                    this.mInputThread.join();
                } catch (InterruptedException unused) {
                }
            }
            this.mInputThread = null;
        }
        if (this.mConnection != null) {
            this.mConnection.releaseInterface(this.mDevice.getInterface(this.mInterfaceIndex));
            this.mConnection.close();
            this.mConnection = null;
        }
    }

    @Override // org.libsdl.app.HIDDevice
    public UsbDevice getDevice() {
        return this.mDevice;
    }

    public String getDeviceName() {
        return getManufacturerName() + " " + getProductName() + "(0x" + String.format("%x", Integer.valueOf(getVendorId())) + "/0x" + String.format("%x", Integer.valueOf(getProductId())) + ")";
    }

    @Override // org.libsdl.app.HIDDevice
    public boolean getFeatureReport(byte[] bArr) {
        int i8;
        boolean z;
        int i9;
        int length = bArr.length;
        byte b8 = bArr[0];
        if (b8 == 0) {
            i8 = length - 1;
            z = true;
            i9 = 1;
        } else {
            i8 = length;
            z = false;
            i9 = 0;
        }
        int iControlTransfer = this.mConnection.controlTransfer(161, 1, b8 | 768, this.mInterface, bArr, i9, i8, 1000);
        if (iControlTransfer < 0) {
            StringBuilder sbU = new StringBuilder(); sbU.append("getFeatureReport() returned " ).append(iControlTransfer).append(" on device " );
            sbU.append(getDeviceName());
            Log.w(TAG, sbU.toString());
            return false;
        }
        if (z) {
            iControlTransfer++;
            i8++;
        }
        this.mManager.HIDDeviceFeatureReport(this.mDeviceId, iControlTransfer == i8 ? bArr : Arrays.copyOfRange(bArr, 0, iControlTransfer));
        return true;
    }

    @Override // org.libsdl.app.HIDDevice
    public int getId() {
        return this.mDeviceId;
    }

    public String getIdentifier() {
        return String.format("%s/%x/%x/%d", this.mDevice.getDeviceName(), Integer.valueOf(this.mDevice.getVendorId()), Integer.valueOf(this.mDevice.getProductId()), Integer.valueOf(this.mInterfaceIndex));
    }

    @Override // org.libsdl.app.HIDDevice
    public String getManufacturerName() {
        String manufacturerName = this.mDevice.getManufacturerName();
        return manufacturerName == null ? String.format("%x", Integer.valueOf(getVendorId())) : manufacturerName;
    }

    @Override // org.libsdl.app.HIDDevice
    public int getProductId() {
        return this.mDevice.getProductId();
    }

    @Override // org.libsdl.app.HIDDevice
    public String getProductName() {
        String productName = this.mDevice.getProductName();
        return productName == null ? String.format("%x", Integer.valueOf(getProductId())) : productName;
    }

    @Override // org.libsdl.app.HIDDevice
    public String getSerialNumber() {
        String serialNumber;
        try {
            serialNumber = this.mDevice.getSerialNumber();
        } catch (SecurityException unused) {
            serialNumber = null;
        }
        return serialNumber == null ? "" : serialNumber;
    }

    @Override // org.libsdl.app.HIDDevice
    public int getVendorId() {
        return this.mDevice.getVendorId();
    }

    @Override // org.libsdl.app.HIDDevice
    public int getVersion() {
        return 0;
    }

    @Override // org.libsdl.app.HIDDevice
    public boolean open() {
        UsbDeviceConnection usbDeviceConnectionOpenDevice = this.mManager.getUSBManager().openDevice(this.mDevice);
        this.mConnection = usbDeviceConnectionOpenDevice;
        if (usbDeviceConnectionOpenDevice == null) {
            Log.w(TAG, "Unable to open USB device " + getDeviceName());
            return false;
        }
        UsbInterface usbInterface = this.mDevice.getInterface(this.mInterfaceIndex);
        if (!this.mConnection.claimInterface(usbInterface, true)) {
            Log.w(TAG, "Failed to claim interfaces on USB device " + getDeviceName());
            close();
            return false;
        }
        for (int i8 = 0; i8 < usbInterface.getEndpointCount(); i8++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i8);
            int direction = endpoint.getDirection();
            if (direction != 0) {
                if (direction == 128 && this.mInputEndpoint == null) {
                    this.mInputEndpoint = endpoint;
                }
            } else if (this.mOutputEndpoint == null) {
                this.mOutputEndpoint = endpoint;
            }
        }
        if (this.mInputEndpoint == null || this.mOutputEndpoint == null) {
            Log.w(TAG, "Missing required endpoint on USB device " + getDeviceName());
            close();
            return false;
        }
        this.mRunning = true;
        InputThread inputThread = new InputThread();
        this.mInputThread = inputThread;
        inputThread.start();
        return true;
    }

    @Override // org.libsdl.app.HIDDevice
    public int sendFeatureReport(byte[] bArr) {
        int i8;
        int length = bArr.length;
        boolean z = false;
        byte b8 = bArr[0];
        if (b8 == 0) {
            length--;
            z = true;
            i8 = 1;
        } else {
            i8 = 0;
        }
        int i9 = length;
        int iControlTransfer = this.mConnection.controlTransfer(33, 9, b8 | 768, this.mInterface, bArr, i8, i9, 1000);
        if (iControlTransfer >= 0) {
            return z ? i9 + 1 : i9;
        }
        StringBuilder sbU = new StringBuilder(); sbU.append("sendFeatureReport() returned " ).append(iControlTransfer).append(" on device " );
        sbU.append(getDeviceName());
        Log.w(TAG, sbU.toString());
        return -1;
    }

    @Override // org.libsdl.app.HIDDevice
    public int sendOutputReport(byte[] bArr) {
        int iBulkTransfer = this.mConnection.bulkTransfer(this.mOutputEndpoint, bArr, bArr.length, 1000);
        if (iBulkTransfer != bArr.length) {
            StringBuilder sbU = new StringBuilder(); sbU.append("sendOutputReport() returned " ).append(iBulkTransfer).append(" on device " );
            sbU.append(getDeviceName());
            Log.w(TAG, sbU.toString());
        }
        return iBulkTransfer;
    }

    @Override // org.libsdl.app.HIDDevice
    public void setFrozen(boolean z) {
        this.mFrozen = z;
    }

    @Override // org.libsdl.app.HIDDevice
    public void shutdown() {
        close();
        this.mManager = null;
    }
}
