import type { EventEmitter, EventSubscription } from 'react-native';
import UsbSerialportForAndroid from './native_module';

const DataReceivedEvent = 'usbSerialPortDataReceived';
const ErrorEvent = 'usbSerialPortError';

export interface EventData {
  deviceId: number;
  /**
   * hex format
   */
  data: string;
}

export interface ErrorEventData {
  deviceId: number;
  deviceKey: string;
  error: string;
  errorMessage: string;
  /**
   * True if the error is likely caused by physical USB disconnection
   */
  isDisconnection: boolean;
  portIndex: number;
  realDeviceId: number;
}

export interface UsbDeviceEventData {
  /**
   * Physical device ID
   */
  deviceId: number;
  /**
   * USB Vendor ID
   */
  vendorId: number;
  /**
   * USB Product ID
   */
  productId: number;
  /**
   * Device name (path in filesystem, e.g., /dev/bus/usb/001/002)
   */
  deviceName: string;
  /**
   * Number of serial ports available on this device
   */
  portCount: number;
  /**
   * Whether a driver is available for this device
   */
  hasDriver: boolean;
  /**
   * Virtual device IDs for all ports (for multi-port devices)
   * Format: [deviceId * 100 + portIndex, ...]
   */
  portIds: number[];
}

export type Listener = (data: EventData) => void;
export type ErrorListener = (error: ErrorEventData) => void;
export type UsbDeviceListener = (device: UsbDeviceEventData) => void;

export default class UsbSerial {
  deviceId: number;
  private eventEmitter: EventEmitter;
  private listeners: Listener[];
  private subscriptions: EventSubscription[];

  constructor(deviceId: number, eventEmitter: EventEmitter) {
    this.deviceId = deviceId;
    this.eventEmitter = eventEmitter;
    this.listeners = [];
    this.subscriptions = [];
  }

  /**
   * Send data with hex string.
   *
   * May return error with these codes:
   * * DEVICE_NOT_OPEN
   * * SEND_FAILED
   *
   * See {@link Codes}
   * @param hexStr
   * @returns
   */
  send(hexStr: string): Promise<null> {
    return UsbSerialportForAndroid.send(this.deviceId, hexStr);
  }

  /**
   * Listen to data received event.
   *
   * @param listener
   * @returns EventSubscription
   */
  onReceived(listener: Listener) {
    const listenerProxy = (event: EventData) => {
      if (event.deviceId !== this.deviceId) {
        return;
      }
      if (!event.data) {
        return;
      }

      listener(event);
    };

    this.listeners.push(listenerProxy);
    const sub = this.eventEmitter.addListener(DataReceivedEvent, listenerProxy);
    this.subscriptions.push(sub);
    return sub;
  }

  /**
   * Listen to error events, including physical USB disconnection.
   *
   * @param listener
   * @returns EventSubscription
   */
  onError(listener: ErrorListener) {
    const listenerProxy = (event: ErrorEventData) => {
      if (event.deviceId !== this.deviceId) {
        return;
      }

      listener(event);
    };

    const sub = this.eventEmitter.addListener(ErrorEvent, listenerProxy);
    this.subscriptions.push(sub);
    return sub;
  }

  /**
   *
   * May return error with these codes:
   * * DEVICE_NOT_OPEN_OR_CLOSED
   *
   * See {@link Codes}
   * @returns Promise<null>
   */
  close(): Promise<any> {
    for (const sub of this.subscriptions) {
      sub.remove();
    }
    return UsbSerialportForAndroid.close(this.deviceId);
  }
}
