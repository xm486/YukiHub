package org.libsdl.app;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.UUID;

/* JADX INFO: loaded from: classes.dex */
class HIDDeviceBLESteamController extends BluetoothGattCallback implements HIDDevice {
    private static final int CHROMEBOOK_CONNECTION_CHECK_INTERVAL = 10000;
    private static final String TAG = "hidapi";
    private static final int TRANSPORT_AUTO = 0;
    private static final int TRANSPORT_BREDR = 1;
    private static final int TRANSPORT_LE = 2;
    private BluetoothDevice mDevice;
    private int mDeviceId;
    private boolean mIsChromebook;
    private boolean mIsRegistered;
    private HIDDeviceManager mManager;
    public static final UUID steamControllerService = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3");
    public static final UUID inputCharacteristic = UUID.fromString("100F6C33-1735-4313-B402-38567131E5F3");
    public static final UUID reportCharacteristic = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3");
    private static final byte[] enterValveMode = {-64, -121, 3, 8, 7, 0};
    private boolean mIsConnected = false;
    private boolean mIsReconnecting = false;
    private boolean mFrozen = false;
    GattOperation mCurrentOperation = null;
    private LinkedList<GattOperation> mOperations = new LinkedList<>();
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private BluetoothGatt mGatt = connectGatt();

    public HIDDeviceBLESteamController(HIDDeviceManager hIDDeviceManager, BluetoothDevice bluetoothDevice) {
        this.mIsRegistered = false;
        this.mIsChromebook = false;
        this.mManager = hIDDeviceManager;
        this.mDevice = bluetoothDevice;
        this.mDeviceId = hIDDeviceManager.getDeviceIDForIdentifier(getIdentifier());
        this.mIsRegistered = false;
        this.mIsChromebook = this.mManager.getContext().getPackageManager().hasSystemFeature("org.chromium.arc.device_management");
    }

    private BluetoothGatt connectGatt(boolean z) {
        if (Build.VERSION.SDK_INT < 23) {
            return this.mDevice.connectGatt(this.mManager.getContext(), z, this);
        }
        try {
            return this.mDevice.connectGatt(this.mManager.getContext(), z, this, 2);
        } catch (Exception unused) {
            return this.mDevice.connectGatt(this.mManager.getContext(), z, this);
        }
    }

    private void enableNotification(UUID uuid) {
        queueGattOperation(GattOperation.enableNotification(this.mGatt, uuid));
    }

    private void executeNextGattOperation() {
        synchronized (this.mOperations) {
            try {
                if (this.mCurrentOperation != null) {
                    return;
                }
                if (this.mOperations.isEmpty()) {
                    return;
                }
                this.mCurrentOperation = this.mOperations.removeFirst();
                this.mHandler.post(new Runnable() { // from class: org.libsdl.app.HIDDeviceBLESteamController.2
                    @Override // java.lang.Runnable
                    public void run() {
                        synchronized (HIDDeviceBLESteamController.this.mOperations) {
                            try {
                                GattOperation gattOperation = HIDDeviceBLESteamController.this.mCurrentOperation;
                                if (gattOperation == null) {
                                    Log.e(HIDDeviceBLESteamController.TAG, "Current operation null in executor?");
                                } else {
                                    gattOperation.run();
                                }
                            } catch (Throwable th) {
                                throw th;
                            }
                        }
                    }
                });
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    private void finishCurrentGattOperation() {
        GattOperation gattOperation;
        synchronized (this.mOperations) {
            try {
                gattOperation = this.mCurrentOperation;
                if (gattOperation != null) {
                    this.mCurrentOperation = null;
                } else {
                    gattOperation = null;
                }
            } catch (Throwable th) {
                throw th;
            }
        }
        if (gattOperation != null && !gattOperation.finish()) {
            this.mOperations.addFirst(gattOperation);
        }
        executeNextGattOperation();
    }

    private boolean isRegistered() {
        return this.mIsRegistered;
    }

    private boolean probeService(HIDDeviceBLESteamController hIDDeviceBLESteamController) {
        if (isRegistered()) {
            return true;
        }
        if (!this.mIsConnected) {
            return false;
        }
        Log.v(TAG, "probeService controller=" + hIDDeviceBLESteamController);
        for (BluetoothGattService bluetoothGattService : this.mGatt.getServices()) {
            if (bluetoothGattService.getUuid().equals(steamControllerService)) {
                Log.v(TAG, "Found Valve steam controller service " + bluetoothGattService.getUuid());
                for (BluetoothGattCharacteristic bluetoothGattCharacteristic : bluetoothGattService.getCharacteristics()) {
                    if (bluetoothGattCharacteristic.getUuid().equals(inputCharacteristic)) {
                        Log.v(TAG, "Found input characteristic");
                        if (bluetoothGattCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) != null) {
                            enableNotification(bluetoothGattCharacteristic.getUuid());
                        }
                    }
                }
                return true;
            }
        }
        if (this.mGatt.getServices().size() == 0 && this.mIsChromebook && !this.mIsReconnecting) {
            Log.e(TAG, "Chromebook: Discovered services were empty; this almost certainly means the BtGatt.ContextMap bug has bitten us.");
            this.mIsConnected = false;
            this.mIsReconnecting = true;
            this.mGatt.disconnect();
            this.mGatt = connectGatt(false);
        }
        return false;
    }

    private void queueGattOperation(GattOperation gattOperation) {
        synchronized (this.mOperations) {
            this.mOperations.add(gattOperation);
        }
        executeNextGattOperation();
    }

    private void setRegistered() {
        this.mIsRegistered = true;
    }

    public void checkConnectionForChromebookIssue() {
        if (this.mIsChromebook) {
            int connectionState = getConnectionState();
            if (connectionState == 0) {
                Log.v(TAG, "Chromebook: We have either been disconnected, or the Chromebook BtGatt.ContextMap bug has bitten us.  Attempting a disconnect/reconnect, but we may not be able to recover.");
                this.mIsReconnecting = true;
                this.mGatt.disconnect();
                this.mGatt = connectGatt(false);
            } else if (connectionState == 1) {
                Log.v(TAG, "Chromebook: We're still trying to connect.  Waiting a bit longer.");
            } else if (connectionState == 2) {
                if (!this.mIsConnected) {
                    Log.v(TAG, "Chromebook: We are in a very bad state; the controller shows as connected in the underlying Bluetooth layer, but we never received a callback.  Forcing a reconnect.");
                    this.mIsReconnecting = true;
                    this.mGatt.disconnect();
                    this.mGatt = connectGatt(false);
                } else if (isRegistered()) {
                    Log.v(TAG, "Chromebook: We are connected, and registered.  Everything's good!");
                    return;
                } else if (this.mGatt.getServices().size() > 0) {
                    Log.v(TAG, "Chromebook: We are connected to a controller, but never got our registration.  Trying to recover.");
                    probeService(this);
                } else {
                    Log.v(TAG, "Chromebook: We are connected to a controller, but never discovered services.  Trying to recover.");
                    this.mIsReconnecting = true;
                    this.mGatt.disconnect();
                    this.mGatt = connectGatt(false);
                }
            }
            this.mHandler.postDelayed(new Runnable() { // from class: org.libsdl.app.HIDDeviceBLESteamController.1
                @Override // java.lang.Runnable
                public void run() {
                    HIDDeviceBLESteamController.this.checkConnectionForChromebookIssue();
                }
            }, 10000L);
        }
    }

    @Override // org.libsdl.app.HIDDevice
    public void close() {
    }

    public int getConnectionState() {
        BluetoothManager bluetoothManager;
        Context context = this.mManager.getContext();
        if (context == null || (bluetoothManager = (BluetoothManager) context.getSystemService("bluetooth")) == null) {
            return 0;
        }
        return bluetoothManager.getConnectionState(this.mDevice, 7);
    }

    @Override // org.libsdl.app.HIDDevice
    public UsbDevice getDevice() {
        return null;
    }

    @Override // org.libsdl.app.HIDDevice
    public boolean getFeatureReport(byte[] bArr) {
        if (isRegistered()) {
            readCharacteristic(reportCharacteristic);
            return true;
        }
        Log.e(TAG, "Attempted getFeatureReport before Steam Controller is registered!");
        if (!this.mIsConnected) {
            return false;
        }
        probeService(this);
        return false;
    }

    public BluetoothGatt getGatt() {
        return this.mGatt;
    }

    @Override // org.libsdl.app.HIDDevice
    public int getId() {
        return this.mDeviceId;
    }

    public String getIdentifier() {
        return "SteamController." + this.mDevice.getAddress();
    }

    @Override // org.libsdl.app.HIDDevice
    public String getManufacturerName() {
        return "Valve Corporation";
    }

    @Override // org.libsdl.app.HIDDevice
    public int getProductId() {
        return 4358;
    }

    @Override // org.libsdl.app.HIDDevice
    public String getProductName() {
        return "Steam Controller";
    }

    @Override // org.libsdl.app.HIDDevice
    public String getSerialNumber() {
        return "12345";
    }

    @Override // org.libsdl.app.HIDDevice
    public int getVendorId() {
        return 10462;
    }

    @Override // org.libsdl.app.HIDDevice
    public int getVersion() {
        return 0;
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        if (!bluetoothGattCharacteristic.getUuid().equals(inputCharacteristic) || this.mFrozen) {
            return;
        }
        this.mManager.HIDDeviceInputReport(getId(), bluetoothGattCharacteristic.getValue());
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onCharacteristicRead(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, int i8) {
        if (bluetoothGattCharacteristic.getUuid().equals(reportCharacteristic) && !this.mFrozen) {
            this.mManager.HIDDeviceFeatureReport(getId(), bluetoothGattCharacteristic.getValue());
        }
        finishCurrentGattOperation();
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onCharacteristicWrite(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, int i8) {
        if (bluetoothGattCharacteristic.getUuid().equals(reportCharacteristic) && !isRegistered()) {
            Log.v(TAG, "Registering Steam Controller with ID: " + getId());
            this.mManager.HIDDeviceConnected(getId(), getIdentifier(), getVendorId(), getProductId(), getSerialNumber(), getVersion(), getManufacturerName(), getProductName(), 0, 0, 0, 0);
            setRegistered();
        }
        finishCurrentGattOperation();
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int i8, int i9) {
        this.mIsReconnecting = false;
        if (i9 != 2) {
            if (i9 == 0) {
                this.mIsConnected = false;
            }
        } else {
            this.mIsConnected = true;
            if (isRegistered()) {
                return;
            }
            this.mHandler.post(new Runnable() { // from class: org.libsdl.app.HIDDeviceBLESteamController.3
                @Override // java.lang.Runnable
                public void run() {
                    HIDDeviceBLESteamController.this.mGatt.discoverServices();
                }
            });
        }
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onDescriptorRead(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor bluetoothGattDescriptor, int i8) {
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor bluetoothGattDescriptor, int i8) {
        BluetoothGattCharacteristic characteristic;
        BluetoothGattCharacteristic characteristic2 = bluetoothGattDescriptor.getCharacteristic();
        if (characteristic2.getUuid().equals(inputCharacteristic) && (characteristic = characteristic2.getService().getCharacteristic(reportCharacteristic)) != null) {
            Log.v(TAG, "Writing report characteristic to enter valve mode");
            characteristic.setValue(enterValveMode);
            bluetoothGatt.writeCharacteristic(characteristic);
        }
        finishCurrentGattOperation();
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onMtuChanged(BluetoothGatt bluetoothGatt, int i8, int i9) {
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onReadRemoteRssi(BluetoothGatt bluetoothGatt, int i8, int i9) {
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onReliableWriteCompleted(BluetoothGatt bluetoothGatt, int i8) {
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int i8) {
        if (i8 == 0) {
            if (bluetoothGatt.getServices().size() != 0) {
                probeService(this);
                return;
            }
            Log.v(TAG, "onServicesDiscovered returned zero services; something has gone horribly wrong down in Android's Bluetooth stack.");
            this.mIsReconnecting = true;
            this.mIsConnected = false;
            bluetoothGatt.disconnect();
            this.mGatt = connectGatt(false);
        }
    }

    @Override // org.libsdl.app.HIDDevice
    public boolean open() {
        return true;
    }

    public void readCharacteristic(UUID uuid) {
        queueGattOperation(GattOperation.readCharacteristic(this.mGatt, uuid));
    }

    public void reconnect() {
        if (getConnectionState() != 2) {
            this.mGatt.disconnect();
            this.mGatt = connectGatt();
        }
    }

    @Override // org.libsdl.app.HIDDevice
    public int sendFeatureReport(byte[] bArr) {
        if (isRegistered()) {
            writeCharacteristic(reportCharacteristic, Arrays.copyOfRange(bArr, 1, bArr.length - 1));
            return bArr.length;
        }
        Log.e(TAG, "Attempted sendFeatureReport before Steam Controller is registered!");
        if (!this.mIsConnected) {
            return -1;
        }
        probeService(this);
        return -1;
    }

    @Override // org.libsdl.app.HIDDevice
    public int sendOutputReport(byte[] bArr) {
        if (isRegistered()) {
            writeCharacteristic(reportCharacteristic, bArr);
            return bArr.length;
        }
        Log.e(TAG, "Attempted sendOutputReport before Steam Controller is registered!");
        if (!this.mIsConnected) {
            return -1;
        }
        probeService(this);
        return -1;
    }

    @Override // org.libsdl.app.HIDDevice
    public void setFrozen(boolean z) {
        this.mFrozen = z;
    }

    @Override // org.libsdl.app.HIDDevice
    public void shutdown() {
        close();
        BluetoothGatt bluetoothGatt = this.mGatt;
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            this.mGatt = null;
        }
        this.mManager = null;
        this.mIsRegistered = false;
        this.mIsConnected = false;
        this.mOperations.clear();
    }

    public void writeCharacteristic(UUID uuid, byte[] bArr) {
        queueGattOperation(GattOperation.writeCharacteristic(this.mGatt, uuid, bArr));
    }

    public static class GattOperation {
        BluetoothGatt mGatt;
        Operation mOp;
        boolean mResult = true;
        UUID mUuid;
        byte[] mValue;

        public enum Operation {
            CHR_READ,
            CHR_WRITE,
            ENABLE_NOTIFICATION
        }

        private GattOperation(BluetoothGatt bluetoothGatt, Operation operation, UUID uuid) {
            this.mGatt = bluetoothGatt;
            this.mOp = operation;
            this.mUuid = uuid;
        }

        public static GattOperation enableNotification(BluetoothGatt bluetoothGatt, UUID uuid) {
            return new GattOperation(bluetoothGatt, Operation.ENABLE_NOTIFICATION, uuid);
        }

        private BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
            BluetoothGattService service = this.mGatt.getService(HIDDeviceBLESteamController.steamControllerService);
            if (service == null) {
                return null;
            }
            return service.getCharacteristic(uuid);
        }

        public static GattOperation readCharacteristic(BluetoothGatt bluetoothGatt, UUID uuid) {
            return new GattOperation(bluetoothGatt, Operation.CHR_READ, uuid);
        }

        public static GattOperation writeCharacteristic(BluetoothGatt bluetoothGatt, UUID uuid, byte[] bArr) {
            return new GattOperation(bluetoothGatt, Operation.CHR_WRITE, uuid, bArr);
        }

        public boolean finish() {
            return this.mResult;
        }

        public void run() {
            BluetoothGattCharacteristic characteristic;
            BluetoothGattDescriptor descriptor;
            byte[] bArr;
            int iOrdinal = this.mOp.ordinal();
            if (iOrdinal == 0) {
                if (this.mGatt.readCharacteristic(getCharacteristic(this.mUuid))) {
                    this.mResult = true;
                    return;
                }
                Log.e(HIDDeviceBLESteamController.TAG, "Unable to read characteristic " + this.mUuid.toString());
                this.mResult = false;
                return;
            }
            if (iOrdinal == 1) {
                BluetoothGattCharacteristic characteristic2 = getCharacteristic(this.mUuid);
                characteristic2.setValue(this.mValue);
                if (this.mGatt.writeCharacteristic(characteristic2)) {
                    this.mResult = true;
                    return;
                }
                Log.e(HIDDeviceBLESteamController.TAG, "Unable to write characteristic " + this.mUuid.toString());
                this.mResult = false;
                return;
            }
            if (iOrdinal != 2 || (characteristic = getCharacteristic(this.mUuid)) == null || (descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))) == null) {
                return;
            }
            int properties = characteristic.getProperties();
            if ((properties & 16) == 16) {
                bArr = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            } else {
                if ((properties & 32) != 32) {
                    Log.e(HIDDeviceBLESteamController.TAG, "Unable to start notifications on input characteristic");
                    this.mResult = false;
                    return;
                }
                bArr = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            }
            this.mGatt.setCharacteristicNotification(characteristic, true);
            descriptor.setValue(bArr);
            if (this.mGatt.writeDescriptor(descriptor)) {
                this.mResult = true;
                return;
            }
            Log.e(HIDDeviceBLESteamController.TAG, "Unable to write descriptor " + this.mUuid.toString());
            this.mResult = false;
        }

        private GattOperation(BluetoothGatt bluetoothGatt, Operation operation, UUID uuid, byte[] bArr) {
            this.mGatt = bluetoothGatt;
            this.mOp = operation;
            this.mUuid = uuid;
            this.mValue = bArr;
        }
    }

    private BluetoothGatt connectGatt() {
        return connectGatt(false);
    }
}
