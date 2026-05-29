package org.libsdl.app;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
public class HIDDeviceManager {
    private static final String ACTION_USB_PERMISSION = "org.libsdl.app.USB_PERMISSION";
    private static final String TAG = "hidapi";
    private static HIDDeviceManager sManager;
    private static int sManagerRefCount;
    private BluetoothManager mBluetoothManager;
    private Context mContext;
    private Handler mHandler;
    private boolean mIsChromebook;
    private List<BluetoothDevice> mLastBluetoothDevices;
    private int mNextDeviceId;
    private SharedPreferences mSharedPreferences;
    private UsbManager mUsbManager;
    private HashMap<Integer, HIDDevice> mDevicesById = new HashMap<>();
    private HashMap<BluetoothDevice, HIDDeviceBLESteamController> mBluetoothDevices = new HashMap<>();
    private final BroadcastReceiver mUsbBroadcast = new BroadcastReceiver() { // from class: org.libsdl.app.HIDDeviceManager.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals("android.hardware.usb.action.USB_DEVICE_ATTACHED")) {
                HIDDeviceManager.this.handleUsbDeviceAttached((UsbDevice) intent.getParcelableExtra("device"));
            } else if (action.equals("android.hardware.usb.action.USB_DEVICE_DETACHED")) {
                HIDDeviceManager.this.handleUsbDeviceDetached((UsbDevice) intent.getParcelableExtra("device"));
            } else if (action.equals(HIDDeviceManager.ACTION_USB_PERMISSION)) {
                HIDDeviceManager.this.handleUsbDevicePermission((UsbDevice) intent.getParcelableExtra("device"), intent.getBooleanExtra("permission", false));
            }
        }
    };
    private final BroadcastReceiver mBluetoothBroadcast = new BroadcastReceiver() { // from class: org.libsdl.app.HIDDeviceManager.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals("android.bluetooth.device.action.ACL_CONNECTED")) {
                BluetoothDevice bluetoothDevice = (BluetoothDevice) intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                Log.d(HIDDeviceManager.TAG, "Bluetooth device connected: " + bluetoothDevice);
                if (HIDDeviceManager.this.isSteamController(bluetoothDevice)) {
                    HIDDeviceManager.this.connectBluetoothDevice(bluetoothDevice);
                }
            }
            if (action.equals("android.bluetooth.device.action.ACL_DISCONNECTED")) {
                BluetoothDevice bluetoothDevice2 = (BluetoothDevice) intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                Log.d(HIDDeviceManager.TAG, "Bluetooth device disconnected: " + bluetoothDevice2);
                HIDDeviceManager.this.disconnectBluetoothDevice(bluetoothDevice2);
            }
        }
    };

    private HIDDeviceManager(Context context) {
        this.mNextDeviceId = 0;
        this.mSharedPreferences = null;
        this.mIsChromebook = false;
        this.mContext = context;
        HIDDeviceRegisterCallback();
        this.mSharedPreferences = this.mContext.getSharedPreferences(TAG, 0);
        this.mIsChromebook = this.mContext.getPackageManager().hasSystemFeature("org.chromium.arc.device_management");
        this.mNextDeviceId = this.mSharedPreferences.getInt("next_device_id", 0);
    }

    private native void HIDDeviceRegisterCallback();

    private native void HIDDeviceReleaseCallback();

    public static HIDDeviceManager acquire(Context context) {
        if (sManagerRefCount == 0) {
            sManager = new HIDDeviceManager(context);
        }
        sManagerRefCount++;
        return sManager;
    }

    private void close() {
        shutdownUSB();
        shutdownBluetooth();
        synchronized (this) {
            try {
                Iterator<HIDDevice> it = this.mDevicesById.values().iterator();
                while (it.hasNext()) {
                    it.next().shutdown();
                }
                this.mDevicesById.clear();
                this.mBluetoothDevices.clear();
                HIDDeviceReleaseCallback();
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    private void connectHIDDeviceUSB(UsbDevice usbDevice) {
        HIDDeviceManager hIDDeviceManager = this;
        synchronized (this) {
            int i8 = 0;
            int i9 = 0;
            while (i9 < usbDevice.getInterfaceCount()) {
                try {
                    UsbInterface usbInterface = usbDevice.getInterface(i9);
                    if (hIDDeviceManager.isHIDDeviceInterface(usbDevice, usbInterface)) {
                        int id = 1 << usbInterface.getId();
                        if ((i8 & id) == 0) {
                            int i10 = i8 | id;
                            HIDDeviceUSB hIDDeviceUSB = new HIDDeviceUSB(hIDDeviceManager, usbDevice, i9);
                            int id2 = hIDDeviceUSB.getId();
                            hIDDeviceManager.mDevicesById.put(Integer.valueOf(id2), hIDDeviceUSB);
                            hIDDeviceManager.HIDDeviceConnected(id2, hIDDeviceUSB.getIdentifier(), hIDDeviceUSB.getVendorId(), hIDDeviceUSB.getProductId(), hIDDeviceUSB.getSerialNumber(), hIDDeviceUSB.getVersion(), hIDDeviceUSB.getManufacturerName(), hIDDeviceUSB.getProductName(), usbInterface.getId(), usbInterface.getInterfaceClass(), usbInterface.getInterfaceSubclass(), usbInterface.getInterfaceProtocol());
                            i8 = i10;
                        }
                    }
                    i9++;
                    hIDDeviceManager = this;
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
    }

    private HIDDevice getDevice(int i8) {
        HIDDevice hIDDevice;
        synchronized (this) {
            try {
                hIDDevice = this.mDevicesById.get(Integer.valueOf(i8));
                if (hIDDevice == null) {
                    Log.v(TAG, "No device for id: " + i8);
                    Log.v(TAG, "Available devices: " + this.mDevicesById.keySet());
                }
            } catch (Throwable th) {
                throw th;
            }
        }
        return hIDDevice;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUsbDeviceAttached(UsbDevice usbDevice) {
        connectHIDDeviceUSB(usbDevice);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUsbDeviceDetached(UsbDevice usbDevice) {
        ArrayList<Integer> arrayList = new ArrayList();
        for (HIDDevice hIDDevice : this.mDevicesById.values()) {
            if (usbDevice.equals(hIDDevice.getDevice())) {
                arrayList.add(Integer.valueOf(hIDDevice.getId()));
            }
        }
        for (Integer num : arrayList) {
            int iIntValue = num.intValue();
            HIDDevice hIDDevice2 = this.mDevicesById.get(num);
            this.mDevicesById.remove(num);
            hIDDevice2.shutdown();
            HIDDeviceDisconnected(iIntValue);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUsbDevicePermission(UsbDevice usbDevice, boolean z) {
        for (HIDDevice hIDDevice : this.mDevicesById.values()) {
            if (usbDevice.equals(hIDDevice.getDevice())) {
                HIDDeviceOpenResult(hIDDevice.getId(), z ? hIDDevice.open() : false);
            }
        }
    }

    private void initializeBluetooth() {
        BluetoothAdapter adapter;
        Log.d(TAG, "Initializing Bluetooth");
        if (Build.VERSION.SDK_INT <= 30 && this.mContext.getPackageManager().checkPermission("android.permission.BLUETOOTH", this.mContext.getPackageName()) != 0) {
            Log.d(TAG, "Couldn't initialize Bluetooth, missing android.permission.BLUETOOTH");
            return;
        }
        if (!this.mContext.getPackageManager().hasSystemFeature("android.hardware.bluetooth_le")) {
            Log.d(TAG, "Couldn't initialize Bluetooth, this version of Android does not support Bluetooth LE");
            return;
        }
        BluetoothManager bluetoothManager = (BluetoothManager) this.mContext.getSystemService("bluetooth");
        this.mBluetoothManager = bluetoothManager;
        if (bluetoothManager == null || (adapter = bluetoothManager.getAdapter()) == null) {
            return;
        }
        for (BluetoothDevice bluetoothDevice : adapter.getBondedDevices()) {
            Log.d(TAG, "Bluetooth device available: " + bluetoothDevice);
            if (isSteamController(bluetoothDevice)) {
                connectBluetoothDevice(bluetoothDevice);
            }
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.bluetooth.device.action.ACL_CONNECTED");
        intentFilter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
        this.mContext.registerReceiver(this.mBluetoothBroadcast, intentFilter);
        if (this.mIsChromebook) {
            this.mHandler = new Handler(Looper.getMainLooper());
            this.mLastBluetoothDevices = new ArrayList();
        }
    }

    private void initializeUSB() {
        UsbManager usbManager = (UsbManager) this.mContext.getSystemService("usb");
        this.mUsbManager = usbManager;
        if (usbManager == null) {
            return;
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED");
        intentFilter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
        intentFilter.addAction(ACTION_USB_PERMISSION);
        this.mContext.registerReceiver(this.mUsbBroadcast, intentFilter);
        Iterator<UsbDevice> it = this.mUsbManager.getDeviceList().values().iterator();
        while (it.hasNext()) {
            handleUsbDeviceAttached(it.next());
        }
    }

    private boolean isHIDDeviceInterface(UsbDevice usbDevice, UsbInterface usbInterface) {
        return usbInterface.getInterfaceClass() == 3 || isXbox360Controller(usbDevice, usbInterface) || isXboxOneController(usbDevice, usbInterface);
    }

    private boolean isXbox360Controller(UsbDevice usbDevice, UsbInterface usbInterface) {
        int[] iArr = {1102, 1103, 1118, 1133, 1390, 1699, 1848, 2047, 3695, 3853, 4152, 4553, 4779, 5168, 5227, 5426, 5604, 5678, 5769, 6473, 7085, 8406, 9414, 11298};
        if (usbInterface.getInterfaceClass() == 255 && usbInterface.getInterfaceSubclass() == 93 && (usbInterface.getInterfaceProtocol() == 1 || usbInterface.getInterfaceProtocol() == 129)) {
            int vendorId = usbDevice.getVendorId();
            for (int i8 = 0; i8 < 24; i8++) {
                if (vendorId == iArr[i8]) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isXboxOneController(UsbDevice usbDevice, UsbInterface usbInterface) {
        int[] iArr = {1118, 1848, 3695, 3853, 5426, 8406, 9414, 11720, 11812};
        if (usbInterface.getId() == 0 && usbInterface.getInterfaceClass() == 255 && usbInterface.getInterfaceSubclass() == 71 && usbInterface.getInterfaceProtocol() == 208) {
            int vendorId = usbDevice.getVendorId();
            for (int i8 = 0; i8 < 9; i8++) {
                if (vendorId == iArr[i8]) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void release(HIDDeviceManager hIDDeviceManager) {
        HIDDeviceManager hIDDeviceManager2 = sManager;
        if (hIDDeviceManager == hIDDeviceManager2) {
            int i8 = sManagerRefCount - 1;
            sManagerRefCount = i8;
            if (i8 == 0) {
                hIDDeviceManager2.close();
                sManager = null;
            }
        }
    }

    private void shutdownBluetooth() {
        try {
            this.mContext.unregisterReceiver(this.mBluetoothBroadcast);
        } catch (Exception unused) {
        }
    }

    private void shutdownUSB() {
        try {
            this.mContext.unregisterReceiver(this.mUsbBroadcast);
        } catch (Exception unused) {
        }
    }

    public native void HIDDeviceConnected(int i8, String str, int i9, int i10, String str2, int i11, String str3, String str4, int i12, int i13, int i14, int i15);

    public native void HIDDeviceDisconnected(int i8);

    public native void HIDDeviceFeatureReport(int i8, byte[] bArr);

    public native void HIDDeviceInputReport(int i8, byte[] bArr);

    public native void HIDDeviceOpenPending(int i8);

    public native void HIDDeviceOpenResult(int i8, boolean z);

    public void chromebookConnectionHandler() {
        if (this.mIsChromebook) {
            ArrayList arrayList = new ArrayList();
            ArrayList arrayList2 = new ArrayList();
            List<BluetoothDevice> connectedDevices = this.mBluetoothManager.getConnectedDevices(7);
            for (BluetoothDevice bluetoothDevice : connectedDevices) {
                if (!this.mLastBluetoothDevices.contains(bluetoothDevice)) {
                    arrayList2.add(bluetoothDevice);
                }
            }
            for (BluetoothDevice bluetoothDevice2 : this.mLastBluetoothDevices) {
                if (!connectedDevices.contains(bluetoothDevice2)) {
                    arrayList.add(bluetoothDevice2);
                }
            }
            this.mLastBluetoothDevices = connectedDevices;
            Iterator it = arrayList.iterator();
            while (it.hasNext()) {
                disconnectBluetoothDevice((BluetoothDevice) it.next());
            }
            Iterator it2 = arrayList2.iterator();
            while (it2.hasNext()) {
                connectBluetoothDevice((BluetoothDevice) it2.next());
            }
            this.mHandler.postDelayed(new Runnable() { // from class: org.libsdl.app.HIDDeviceManager.3
                @Override // java.lang.Runnable
                public void run() {
                    HIDDeviceManager.this.chromebookConnectionHandler();
                }
            }, 10000L);
        }
    }

    public void closeDevice(int i8) {
        try {
            Log.v(TAG, "closeDevice deviceID=" + i8);
            HIDDevice device = getDevice(i8);
            if (device == null) {
                HIDDeviceDisconnected(i8);
            } else {
                device.close();
            }
        } catch (Exception e8) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e8));
        }
    }

    public boolean connectBluetoothDevice(BluetoothDevice bluetoothDevice) {
        Log.v(TAG, "connectBluetoothDevice device=" + bluetoothDevice);
        synchronized (this) {
            try {
                if (!this.mBluetoothDevices.containsKey(bluetoothDevice)) {
                    HIDDeviceBLESteamController hIDDeviceBLESteamController = new HIDDeviceBLESteamController(this, bluetoothDevice);
                    int id = hIDDeviceBLESteamController.getId();
                    this.mBluetoothDevices.put(bluetoothDevice, hIDDeviceBLESteamController);
                    this.mDevicesById.put(Integer.valueOf(id), hIDDeviceBLESteamController);
                    return true;
                }
                Log.v(TAG, "Steam controller with address " + bluetoothDevice + " already exists, attempting reconnect");
                this.mBluetoothDevices.get(bluetoothDevice).reconnect();
                return false;
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public void disconnectBluetoothDevice(BluetoothDevice bluetoothDevice) {
        synchronized (this) {
            try {
                HIDDeviceBLESteamController hIDDeviceBLESteamController = this.mBluetoothDevices.get(bluetoothDevice);
                if (hIDDeviceBLESteamController == null) {
                    return;
                }
                int id = hIDDeviceBLESteamController.getId();
                this.mBluetoothDevices.remove(bluetoothDevice);
                this.mDevicesById.remove(Integer.valueOf(id));
                hIDDeviceBLESteamController.shutdown();
                HIDDeviceDisconnected(id);
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public Context getContext() {
        return this.mContext;
    }

    public int getDeviceIDForIdentifier(String str) {
        SharedPreferences.Editor editorEdit = this.mSharedPreferences.edit();
        int i8 = this.mSharedPreferences.getInt(str, 0);
        if (i8 == 0) {
            i8 = this.mNextDeviceId;
            int i9 = i8 + 1;
            this.mNextDeviceId = i9;
            editorEdit.putInt("next_device_id", i9);
        }
        editorEdit.putInt(str, i8);
        editorEdit.commit();
        return i8;
    }

    public boolean getFeatureReport(int i8, byte[] bArr) {
        try {
            HIDDevice device = getDevice(i8);
            if (device != null) {
                return device.getFeatureReport(bArr);
            }
            HIDDeviceDisconnected(i8);
            return false;
        } catch (Exception e8) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e8));
            return false;
        }
    }

    public UsbManager getUSBManager() {
        return this.mUsbManager;
    }

    public boolean initialize(boolean z, boolean z8) {
        Log.v(TAG, "initialize(" + z + ", " + z8 + ")");
        if (z) {
            initializeUSB();
        }
        if (!z8) {
            return true;
        }
        initializeBluetooth();
        return true;
    }

    public boolean isSteamController(BluetoothDevice bluetoothDevice) {
        return (bluetoothDevice == null || bluetoothDevice.getName() == null || !bluetoothDevice.getName().equals("SteamController") || (bluetoothDevice.getType() & 2) == 0) ? false : true;
    }

    public boolean openDevice(int i8) {
        Log.v(TAG, "openDevice deviceID=" + i8);
        HIDDevice device = getDevice(i8);
        if (device == null) {
            HIDDeviceDisconnected(i8);
            return false;
        }
        UsbDevice device2 = device.getDevice();
        if (device2 == null || this.mUsbManager.hasPermission(device2)) {
            try {
                return device.open();
            } catch (Exception e8) {
                Log.e(TAG, "Got exception: " + Log.getStackTraceString(e8));
                return false;
            }
        }
        HIDDeviceOpenPending(i8);
        try {
            this.mUsbManager.requestPermission(device2, PendingIntent.getBroadcast(this.mContext, 0, new Intent(ACTION_USB_PERMISSION), Build.VERSION.SDK_INT >= 31 ? 33554432 : 0));
        } catch (Exception unused) {
            Log.v(TAG, "Couldn't request permission for USB device " + device2);
            HIDDeviceOpenResult(i8, false);
        }
        return false;
    }

    public int sendFeatureReport(int i8, byte[] bArr) {
        try {
            HIDDevice device = getDevice(i8);
            if (device != null) {
                return device.sendFeatureReport(bArr);
            }
            HIDDeviceDisconnected(i8);
            return -1;
        } catch (Exception e8) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e8));
            return -1;
        }
    }

    public int sendOutputReport(int i8, byte[] bArr) {
        try {
            HIDDevice device = getDevice(i8);
            if (device != null) {
                return device.sendOutputReport(bArr);
            }
            HIDDeviceDisconnected(i8);
            return -1;
        } catch (Exception e8) {
            Log.e(TAG, "Got exception: " + Log.getStackTraceString(e8));
            return -1;
        }
    }

    public void setFrozen(boolean z) {
        synchronized (this) {
            try {
                Iterator<HIDDevice> it = this.mDevicesById.values().iterator();
                while (it.hasNext()) {
                    it.next().setFrozen(z);
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }
}
