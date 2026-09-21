package com.zz.infisense.camera;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashMap;
import android.os.Handler;

import static android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_INT;

public class UsbControlBlock {
    private final String TAG = "USBControlBlock";
    private Context context;
    private Handler handler;
    private UsbManager usbManager;
    private UsbDevice usbCamera;
    private UsbInterface usbCameraControlInterface;
    private UsbEndpoint usbCameraControlEndpoint;
    private UsbDeviceConnection usbCameraConnection;
    private String usbCameraName;

    // Same action + message codes as the vendor demo MainActivity.
    public static final String ACTION_USB_PERMISSION = "com.zz.infisense.camera.USB_PERMISSION.";
    public static final int USB_PERMISSION = 0;
    public static final int USB_NOT_PERMIT = 0;
    public static final int USB_PERMIT = 1;
    public static final int USB_ATTACH = 1;
    public static final int USB_DETACH = 2;

    private BroadcastReceiver usbPermissionActionReceiver;


    public UsbControlBlock(Context context, final Handler handler) {
        this.context = context;
        this.handler = handler;
        usbManager = (UsbManager)context.getSystemService(Context.USB_SERVICE);

        usbPermissionActionReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    UsbDevice usbDevice = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        //user choose YES for your previously popup window asking for grant perssion for this usb device
                        Message message = new Message();
                        message.what = USB_PERMISSION;
                        message.arg1 = USB_PERMIT;
                        handler.sendMessage(message);
                        Log.i(TAG, "user choose yes...");
                    }
                    else {
                        //user choose NO for your previously popup window asking for grant perssion for this usb device
                        Message message = new Message();
                        message.what = USB_PERMISSION;
                        message.arg1 = USB_NOT_PERMIT;
                        handler.sendMessage(message);
                        Log.e(TAG, "user choose no...");
                    }
                }
            }
        };
    }

    public boolean getUsbCamera(int vid, int pid) {
        Log.i(TAG, "getUsbCamera");
        usbCamera = getUsbDevice(vid, pid);
        if (usbCamera == null) {
            Log.e(TAG, "usb camera not found...");
            usbCameraName = null;
            usbCameraControlInterface = null;
            usbCameraControlEndpoint = null;
            return false;
        }
        else {
            usbCameraName = getDeviceName();
            usbCameraControlInterface = getUvcControlInterface(usbCamera);
            if (usbCameraControlInterface == null) {
                Log.e(TAG, "control interface of usb camera not found...");
                usbCameraControlEndpoint = null;
                return false;
            }
            else {
                usbCameraControlEndpoint = getUvcControlEndpoint(usbCameraControlInterface);
                if (usbCameraControlEndpoint == null) {
                    Log.e(TAG, "control endpoint of usb camera not found...");
                    return false;
                }
                else {
                    if (!usbManager.hasPermission(usbCamera)) {
                        requestPermission(usbCamera);
                        return false;
                    }
                    else {
                        return true;
                    }
                }
            }
        }
    }

    private UsbDevice getUsbDevice(int vid, int pid) {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            if (vid == device.getVendorId() && pid == device.getProductId()) {
                return device;
            }
        }
        return null;
    }

    private UsbInterface getUvcControlInterface(UsbDevice usbDevice) {
        int interfaceCount = usbDevice.getInterfaceCount();
        for (int interfaceIndex = 0; interfaceIndex < interfaceCount; interfaceIndex++) {
            UsbInterface usbInterface = usbDevice.getInterface(interfaceIndex);
            if (usbInterface.getInterfaceClass() == 14 && usbInterface.getInterfaceSubclass() == 1) {
                return usbInterface;
            }
        }
        return null;
    }

    private UsbEndpoint getUvcControlEndpoint(UsbInterface usbInterface) {
        int endpointCount = usbInterface.getEndpointCount();
        for (int EndpointIndex = 0; EndpointIndex < endpointCount; EndpointIndex++) {
            UsbEndpoint usbEndpoint = usbInterface.getEndpoint(EndpointIndex);
            if (usbEndpoint.getType() == USB_ENDPOINT_XFER_INT ) {
                return usbEndpoint;
            }
        }
        return null;
    }

    /**
     * Demo path: PendingIntent flags=0 and custom USB_PERMISSION action.
     * targetSdk must stay 26 so flags=0 is legal at runtime.
     * RECEIVER_EXPORTED is only a compileSdk 33+ registerReceiver requirement.
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private void requestPermission(UsbDevice usbDevice) {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(usbPermissionActionReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(usbPermissionActionReceiver, filter);
        }

        usbManager.requestPermission(usbDevice, permissionIntent);
    }

    public UsbDevice getDevice() {
        return usbCamera;
    }

    public String getDeviceName() {
        return usbCameraName;
    }

    public int getVenderId() {
        if (usbCamera != null) {
            return usbCamera.getVendorId();
        }
        else {
            return 0;
        }
    }

    public int getProductId() {
        if (usbCamera != null) {
            return usbCamera.getProductId();
        }
        else {
            return 0;
        }
    }

    public int getFileDescriptor() {
        if (usbCamera != null) {
            if (usbCameraConnection == null) {
                usbCameraConnection = usbManager.openDevice(usbCamera);
            }
            return usbCameraConnection.getFileDescriptor();
        }
        else {
            return 0;
        }
    }

    public void close() {
        if (usbCameraConnection != null) {
            usbCameraConnection.close();
            usbCameraConnection = null;
        }
    }

    public int getBusNum() {
        String[] v = !TextUtils.isEmpty(usbCameraName) ? usbCameraName.split("/") : null;
        if (v != null) {
            return (Integer.parseInt(v[v.length-2]));
        }
        else {
            return 0;
        }
    }

    public int getDevNum() {
        String[] v = !TextUtils.isEmpty(usbCameraName) ? usbCameraName.split("/") : null;
        if (v != null) {
            return (Integer.parseInt(v[v.length-1]));
        }
        else {
            return 0;
        }
    }

    public void setIrCameraKbCalibrateValid() {
        if (usbCameraConnection != null) {
            usbCameraConnection.controlTransfer(0X41, 0X20, 1, 0X0341, null, 0, 1000);
        }
    }

    public void setIrCameraKbCalibrateInvalid() {
        if (usbCameraConnection != null) {
            usbCameraConnection.controlTransfer(0X41, 0X20, 0, 0X0341, null, 0, 1000);
        }
    }

    public void irCameraManualShut() {
        if (usbCameraConnection != null) {
            usbCameraConnection.controlTransfer(0X41, 0X20, 0X0000, 0X0345, null, 0, 1000);
        }
    }

    public int getIrCameraObjectDistance() {
        if (usbCameraConnection != null) {
            byte[] data = new byte[2];
            usbCameraConnection.controlTransfer(0XC1, 0X19, 0X0206, 0X0692, data, 2, 1000);
            return (data[1] & 0xff) * 256 + (data[0] & 0xff);
        }
        else {
            return 0;
        }
    }

    public int getShutterMaxTime() {
        if (usbCameraConnection != null) {
            byte[] data = new byte[1];
            usbCameraConnection.controlTransfer(0xC1, 0x19, 0x0103, 0x038a, data, 1, 1000);
            return data[0];
        }
        else {
            return 0;
        }
    }

    public void setShutterMaxTime(byte maxTime) {
        if (usbCameraConnection != null) {
            byte[] data = new byte[1];
            data[0] = maxTime;
            usbCameraConnection.controlTransfer(0x41, 0x20, 0x0103, 0x03c4, data, 1, 1000);
        }
    }
}
