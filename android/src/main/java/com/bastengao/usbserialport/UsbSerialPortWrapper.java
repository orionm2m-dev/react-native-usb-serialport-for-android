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

    private String deviceKey;
    private UsbSerialPort port;
    private EventSender sender;
    private boolean closed = false;
    private SerialInputOutputManager ioManager;

    UsbSerialPortWrapper(String deviceKey, UsbSerialPort port, EventSender sender) {
        this.deviceKey = deviceKey;
        this.port = port;
        this.sender = sender;
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
        // TODO: implement
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
