package com.bastengao.usbserialport;

import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.WritableMap;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.Arrays;

public class UsbSerialPortWrapper implements SerialInputOutputManager.Listener {
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;
    private static final String DataReceivedEvent = "usbSerialPortDataReceived";
    private static final String ErrorEvent = "usbSerialPortError";

    public interface ErrorCallback {
        void onPortError(int deviceId);
    }

    private String deviceKey;
    private int deviceId;
    private UsbSerialPort port;
    private EventSender sender;
    private boolean closed = false;
    private SerialInputOutputManager ioManager;
    private ErrorCallback errorCallback;

    UsbSerialPortWrapper(String deviceKey, int deviceId, UsbSerialPort port, EventSender sender, ErrorCallback errorCallback) {
        this.deviceKey = deviceKey;
        this.deviceId = deviceId;
        this.port = port;
        this.sender = sender;
        this.errorCallback = errorCallback;
        this.ioManager = new SerialInputOutputManager(port, this);
        ioManager.start();
    }

    public void send(byte[] data) throws IOException {
        this.port.write(data, WRITE_WAIT_MILLIS);
    }

    public void onNewData(byte[] data) {
        WritableMap event = Arguments.createMap();
        String hex = UsbSerialportForAndroidModule.bytesToHex(data);
        event.putString("deviceKey", this.deviceKey);
        event.putString("data", hex);

        // Parse deviceKey to get deviceId for backward compatibility
        String[] parts = this.deviceKey.split("_");
        if (parts.length >= 2) {
            try {
                int realDeviceId = Integer.parseInt(parts[0]);
                int portIndex = Integer.parseInt(parts[1]);
                int virtualDeviceId = realDeviceId * 100 + portIndex;
                event.putInt("deviceId", virtualDeviceId);
                event.putInt("portIndex", portIndex);
                event.putInt("realDeviceId", realDeviceId);
            } catch (NumberFormatException e) {
                // Fallback for malformed deviceKey
                event.putInt("deviceId", 0);
                event.putInt("portIndex", 0);
                event.putInt("realDeviceId", 0);
            }
        } else {
            event.putInt("deviceId", 0);
            event.putInt("portIndex", 0);
            event.putInt("realDeviceId", 0);
        }

        Log.d("usbserialport", "📡 Sending event for device " + this.deviceKey + ": " + hex);
        Log.d("usbserialport", "📡 Event data: " + event.toString());
        sender.sendEvent(DataReceivedEvent, event);
    }

    public void onRunError(Exception e) {
        // This callback is triggered when there's an I/O error, including physical USB disconnection
        Log.e("usbserialport", "❌ I/O Error for device " + this.deviceKey + ": " + e.getMessage(), e);

        // Create error event with details
        WritableMap event = Arguments.createMap();
        event.putString("deviceKey", this.deviceKey);
        event.putString("error", e.getClass().getSimpleName());
        event.putString("errorMessage", e.getMessage() != null ? e.getMessage() : "Unknown error");

        // Parse deviceKey to get deviceId for backward compatibility
        String[] parts = this.deviceKey.split("_");
        if (parts.length >= 2) {
            try {
                int realDeviceId = Integer.parseInt(parts[0]);
                int portIndex = Integer.parseInt(parts[1]);
                int virtualDeviceId = realDeviceId * 100 + portIndex;
                event.putInt("deviceId", virtualDeviceId);
                event.putInt("portIndex", portIndex);
                event.putInt("realDeviceId", realDeviceId);
            } catch (NumberFormatException ex) {
                // Fallback for malformed deviceKey
                event.putInt("deviceId", 0);
                event.putInt("portIndex", 0);
                event.putInt("realDeviceId", 0);
            }
        } else {
            event.putInt("deviceId", 0);
            event.putInt("portIndex", 0);
            event.putInt("realDeviceId", 0);
        }

        // Detect if this is a physical disconnection
        // Common disconnection exceptions: IOException with various error messages
        String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        boolean isDisconnection = e instanceof IOException &&
            (e.getMessage() == null ||
             errorMsg.contains("device") ||
             errorMsg.contains("connection") ||
             errorMsg.contains("disconnect") ||
             errorMsg.contains("get_status") ||     // USB status check failed
             errorMsg.contains("status request") || // Status request failed
             errorMsg.contains("not open") ||       // Port not open
             errorMsg.contains("closed") ||         // Port closed
             errorMsg.contains("usb") ||            // Generic USB errors
             errorMsg.contains("i/o error"));       // Generic I/O errors
        event.putBoolean("isDisconnection", isDisconnection);

        Log.d("usbserialport", "📡 Sending error event for device " + this.deviceKey +
              " (disconnection: " + isDisconnection + ")");

        // Send error event to React Native
        sender.sendEvent(ErrorEvent, event);

        // Clean up the connection
        this.close();

        // Notify parent module to remove this wrapper from the map (prevents memory leak)
        if (errorCallback != null) {
            errorCallback.onPortError(this.deviceId);
        }
    }

    public void close() {
        if (closed) {
            return;
        }

        if(ioManager != null) {
            ioManager.setListener(null);
            ioManager.stop();
        }

        this.closed = true;
        try {
            port.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
