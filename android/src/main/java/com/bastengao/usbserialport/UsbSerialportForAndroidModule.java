package com.bastengao.usbserialport;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@ReactModule(name = UsbSerialportForAndroidModule.NAME)
public class UsbSerialportForAndroidModule extends ReactContextBaseJavaModule implements EventSender, UsbSerialPortWrapper.ErrorCallback {
    public static final String NAME = "UsbSerialportForAndroid";
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.LIBRARY_PACKAGE_NAME + ".GRANT_USB";

    // Event names
    private static final String EVENT_USB_ATTACHED = "usbSerialPortAttached";
    private static final String EVENT_USB_DETACHED = "usbSerialPortDetached";

    // Error codes
    public static final String CODE_DEVICE_NOT_FOND = "device_not_found";
    public static final String CODE_DRIVER_NOT_FOND = "driver_not_found";
    public static final String CODE_NOT_ENOUGH_PORTS = "not_enough_ports";
    public static final String CODE_PERMISSION_DENIED = "permission_denied";
    public static final String CODE_OPEN_FAILED = "open_failed";
    public static final String CODE_DEVICE_NOT_OPEN = "device_not_open";
    public static final String CODE_SEND_FAILED = "send_failed";
    public static final String CODE_DEVICE_NOT_OPEN_OR_CLOSED = "device_not_open_or_closed";

    private final ReactApplicationContext reactContext;
    private final Map<Integer, UsbSerialPortWrapper> usbSerialPorts = new HashMap<Integer, UsbSerialPortWrapper>();
    private BroadcastReceiver usbReceiver;

    public UsbSerialportForAndroidModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        registerUsbReceiver();
    }

    @Override
    @NonNull
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put("CODE_DEVICE_NOT_FOND", CODE_DEVICE_NOT_FOND);
        constants.put("CODE_DRIVER_NOT_FOND", CODE_DRIVER_NOT_FOND);
        constants.put("CODE_NOT_ENOUGH_PORTS", CODE_NOT_ENOUGH_PORTS);
        constants.put("CODE_PERMISSION_DENIED", CODE_PERMISSION_DENIED);
        constants.put("CODE_OPEN_FAILED", CODE_OPEN_FAILED);
        constants.put("CODE_DEVICE_NOT_OPEN", CODE_DEVICE_NOT_OPEN);
        constants.put("CODE_SEND_FAILED", CODE_SEND_FAILED);
        constants.put("CODE_DEVICE_NOT_OPEN_OR_CLOSED", CODE_DEVICE_NOT_OPEN_OR_CLOSED);
        return constants;
    }

    @ReactMethod
    public void list(Promise promise) {
        WritableArray devices = Arguments.createArray();
        UsbManager usbManager = (UsbManager) getCurrentActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            // Check if device has a driver to get port information
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver != null && driver.getPorts().size() > 0) {
                // Add each port as a separate device entry for multi-port devices
                for (int portIndex = 0; portIndex < driver.getPorts().size(); portIndex++) {
                    WritableMap d = Arguments.createMap();
                    // Use unique deviceId for each port
                    int virtualDeviceId = device.getDeviceId() * 100 + portIndex;
                    d.putInt("deviceId", virtualDeviceId);
                    d.putInt("realDeviceId", device.getDeviceId());
                    d.putInt("vendorId", device.getVendorId());
                    d.putInt("productId", device.getProductId());
                    d.putInt("portIndex", portIndex);
                    d.putInt("totalPorts", driver.getPorts().size());
                    devices.pushMap(d);
                }
            } else {
                // Add device without driver info for compatibility
                WritableMap d = Arguments.createMap();
                d.putInt("deviceId", device.getDeviceId());
                d.putInt("realDeviceId", device.getDeviceId());
                d.putInt("vendorId", device.getVendorId());
                d.putInt("productId", device.getProductId());
                d.putInt("portIndex", 0);
                d.putInt("totalPorts", 1);
                devices.pushMap(d);
            }
        }
        promise.resolve(devices);
    }

    @ReactMethod
    public void tryRequestPermission(int deviceId, Promise promise) {
        UsbManager usbManager = (UsbManager) getCurrentActivity().getSystemService(Context.USB_SERVICE);
        // Extract real device ID
        int realDeviceId = deviceId >= 100 ? deviceId / 100 : deviceId;
        UsbDevice device = findDevice(realDeviceId);
        if (device == null) {
            promise.reject(CODE_DEVICE_NOT_FOND, "device not found");
            return;
        }

        if (usbManager.hasPermission(device)) {
            promise.resolve(1);
            return;
        }

        PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getCurrentActivity(), 0, new Intent(INTENT_ACTION_GRANT_USB), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        usbManager.requestPermission(device, usbPermissionIntent);
        promise.resolve(0);
    }

    @ReactMethod
    public void hasPermission(int deviceId, Promise promise) {
        UsbManager usbManager = (UsbManager) getCurrentActivity().getSystemService(Context.USB_SERVICE);
        // Extract real device ID
        int realDeviceId = deviceId >= 100 ? deviceId / 100 : deviceId;
        UsbDevice device = findDevice(realDeviceId);
        if (device == null) {
            promise.reject(CODE_DEVICE_NOT_FOND, "device not found");
            return;
        }

        promise.resolve(usbManager.hasPermission(device));
        return;
    }

    @ReactMethod
    public void open(int deviceId, int baudRate, int dataBits, int stopBits, int parity, Promise promise) {
        UsbSerialPortWrapper wrapper = usbSerialPorts.get(deviceId);
        if (wrapper != null) {
            promise.resolve(deviceId);
            return;
        }

        // Extract real device ID and port index
        int realDeviceId = deviceId >= 100 ? deviceId / 100 : deviceId;
        int portIndex = deviceId >= 100 ? deviceId % 100 : 0;

        UsbManager usbManager = (UsbManager) getCurrentActivity().getSystemService(Context.USB_SERVICE);
        UsbDevice device = findDevice(realDeviceId);
        if (device == null) {
            promise.reject(CODE_DEVICE_NOT_FOND, "device not found");
            return;
        }

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            promise.reject(CODE_DRIVER_NOT_FOND, "no driver for device");
            return;
        }
        if (driver.getPorts().size() <= portIndex) {
            promise.reject(CODE_NOT_ENOUGH_PORTS, "port " + portIndex + " not available, device has " + driver.getPorts().size() + " ports");
            return;
        }

        UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
        if(connection == null) {
            if (!usbManager.hasPermission(driver.getDevice())) {
                promise.reject(CODE_PERMISSION_DENIED, "connection failed: permission denied");
            } else {
                promise.reject(CODE_OPEN_FAILED, "connection failed: open failed");
            }
            return;
        }

        UsbSerialPort port = driver.getPorts().get(portIndex);
        try {
            port.open(connection);
            port.setParameters(baudRate, dataBits, stopBits, parity);
        } catch (IOException e) {
            try {
                 port.close();
            } catch (IOException ignored) {}
            promise.reject(CODE_OPEN_FAILED, "connection failed", e);
            return;
        }

        // Create deviceKey from realDeviceId and portIndex
        String deviceKey = realDeviceId + "_" + portIndex;
        wrapper = new UsbSerialPortWrapper(deviceKey, deviceId, port, this, this);
        usbSerialPorts.put(deviceId, wrapper);
        promise.resolve(deviceId);
    }

    @ReactMethod
    public void send(int deviceId, String hexStr, Promise promise) {
        UsbSerialPortWrapper wrapper = usbSerialPorts.get(deviceId);
        if (wrapper == null) {
            promise.reject(CODE_DEVICE_NOT_OPEN, "device not open");
            return;
        }

        byte[] data = hexStringToByteArray(hexStr);
        try {
            wrapper.send(data);
            promise.resolve(null);
        } catch (IOException e) {
            promise.reject(CODE_SEND_FAILED, "send failed", e);
            return;
        }
    }

    @ReactMethod
    public void close(int deviceId, Promise promise) {
        UsbSerialPortWrapper wrapper = usbSerialPorts.get(deviceId);
        if (wrapper == null) {
            promise.reject(CODE_DEVICE_NOT_OPEN_OR_CLOSED, "serial port not open or closed");
            return;
        }

        wrapper.close();
        usbSerialPorts.remove(deviceId);
        promise.resolve(null);
    }

    public void sendEvent(final String eventName, final WritableMap event) {
        reactContext.runOnUiQueueThread(new Runnable() {
            @Override
            public void run() {
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(eventName, event);
            }
        });
    }

    @Override
    public void onPortError(int deviceId) {
        // Called when the wrapper encounters an error (e.g., USB disconnection)
        // Remove the wrapper from the map to prevent memory leaks
        UsbSerialPortWrapper removed = usbSerialPorts.remove(deviceId);
        if (removed != null) {
            Log.d("usbserialport", "🧹 Removed wrapper for deviceId " + deviceId + " after error");
        }
    }

    private UsbDevice findDevice(int deviceId) {
        UsbManager usbManager = (UsbManager) getCurrentActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getDeviceId() == deviceId) {
                return device;
            }
        }

        return null;
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * Register BroadcastReceiver for USB attach/detach events
     */
    private void registerUsbReceiver() {
        try {
            usbReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();

                    if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (device != null) {
                            handleUsbDeviceAttached(device);
                        }
                    } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (device != null) {
                            handleUsbDeviceDetached(device);
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

            reactContext.registerReceiver(usbReceiver, filter);
            Log.d("usbserialport", "📡 USB BroadcastReceiver registered");
        } catch (Exception e) {
            Log.e("usbserialport", "❌ Failed to register USB BroadcastReceiver", e);
        }
    }

    /**
     * Unregister BroadcastReceiver to prevent memory leaks
     */
    private void unregisterUsbReceiver() {
        try {
            if (usbReceiver != null) {
                reactContext.unregisterReceiver(usbReceiver);
                usbReceiver = null;
                Log.d("usbserialport", "🧹 USB BroadcastReceiver unregistered");
            }
        } catch (Exception e) {
            Log.e("usbserialport", "❌ Failed to unregister USB BroadcastReceiver", e);
        }
    }

    /**
     * Handle USB device attached event
     */
    private void handleUsbDeviceAttached(UsbDevice device) {
        Log.d("usbserialport", "🔌 USB device attached: " + device.getDeviceName() +
              " (VID: " + device.getVendorId() + ", PID: " + device.getProductId() + ")");

        WritableMap event = createDeviceEventData(device);
        sendEvent(EVENT_USB_ATTACHED, event);
    }

    /**
     * Handle USB device detached event
     */
    private void handleUsbDeviceDetached(UsbDevice device) {
        Log.d("usbserialport", "🔌 USB device detached: " + device.getDeviceName() +
              " (VID: " + device.getVendorId() + ", PID: " + device.getProductId() + ")");

        WritableMap event = createDeviceEventData(device);
        sendEvent(EVENT_USB_DETACHED, event);
    }

    /**
     * Create device event data with all device information
     */
    private WritableMap createDeviceEventData(UsbDevice device) {
        WritableMap event = Arguments.createMap();

        int realDeviceId = device.getDeviceId();
        event.putInt("deviceId", realDeviceId);
        event.putInt("vendorId", device.getVendorId());
        event.putInt("productId", device.getProductId());
        event.putString("deviceName", device.getDeviceName());

        // Try to get driver info for port information
        try {
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver != null) {
                int portCount = driver.getPorts().size();
                event.putInt("portCount", portCount);
                event.putBoolean("hasDriver", true);

                // Include all port virtual IDs for multi-port devices
                WritableArray portIds = Arguments.createArray();
                for (int i = 0; i < portCount; i++) {
                    int virtualDeviceId = realDeviceId * 100 + i;
                    portIds.pushInt(virtualDeviceId);
                }
                event.putArray("portIds", portIds);
            } else {
                event.putInt("portCount", 0);
                event.putBoolean("hasDriver", false);
                event.putArray("portIds", Arguments.createArray());
            }
        } catch (Exception e) {
            Log.e("usbserialport", "Error getting driver info", e);
            event.putInt("portCount", 0);
            event.putBoolean("hasDriver", false);
            event.putArray("portIds", Arguments.createArray());
        }

        return event;
    }

    /**
     * Called when the React Native catalyst instance is destroyed
     * Clean up resources to prevent memory leaks
     */
    @Override
    public void onCatalystInstanceDestroy() {
        unregisterUsbReceiver();

        // Close all open connections
        for (UsbSerialPortWrapper wrapper : usbSerialPorts.values()) {
            try {
                wrapper.close();
            } catch (Exception e) {
                Log.e("usbserialport", "Error closing wrapper on destroy", e);
            }
        }
        usbSerialPorts.clear();

        super.onCatalystInstanceDestroy();
    }
}
