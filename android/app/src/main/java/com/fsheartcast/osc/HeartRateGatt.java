package com.fsheartcast.osc;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * GATT 心率连接：连接 → 发现服务 → 订阅 0x2A37 通知 → 上报 BPM。
 *
 * 所有 GATT 调用都串行跑在自己的 HandlerThread 上（Android 官方建议），
 * 回调统一抛回主线程，方便上层直接更新 UI。
 */
public final class HeartRateGatt {

    public interface Listener {
        void onLinkUp(String address);

        void onReady(String address);

        void onHeartRate(String address, int bpm);

        void onDisconnected(String address);

        void onError(String address, String message);
    }

    private final Context context;
    private final HandlerThread worker;
    private final Handler handler;
    private final Handler main;

    private BluetoothGatt gatt;
    private volatile String address = "";
    private volatile boolean ready;
    private Listener listener;

    private boolean disconnectReported;
    private int servicesRetry;
    private int cccdRetry;

    public HeartRateGatt(Context context) {
        this.context = context.getApplicationContext();
        this.worker = new HandlerThread("hr-gatt");
        this.worker.start();
        this.handler = new Handler(worker.getLooper());
        this.main = new Handler(Looper.getMainLooper());
    }

    public boolean isReady() {
        return ready;
    }

    public String getAddress() {
        return address;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void connect(final BluetoothDevice device, final String deviceAddress) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                startConnect(device, deviceAddress);
            }
        });
    }

    private void startConnect(BluetoothDevice device, String deviceAddress) {
        closeGatt();
        disconnectReported = false;
        servicesRetry = 0;
        cccdRetry = 0;
        ready = false;
        address = deviceAddress;

        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            gatt = null;
        }

        if (gatt == null) {
            postMainError(deviceAddress, "无法建立 GATT 连接（请确认蓝牙已开启、手表在附近）");
            reportDisconnected();
        }
    }

    public void disconnect() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                BluetoothGatt g = gatt;
                if (g != null) {
                    try {
                        g.disconnect();
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
                closeGatt();
                reportDisconnected();
            }
        });
    }

    public void release() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                closeGatt();
            }
        });
        try {
            worker.quitSafely();
        } catch (Exception ignored) {
            // ignore
        }
    }

    // ---------------------------------------------------------------- GATT

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int status, int newState) {
            final int st = status;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        final String addr = address;
                        closeGatt();
                        postMainError(addr, "GATT 连接失败（错误码 " + st + "）");
                        reportDisconnected();
                    }
                });
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                postMainLinkUp();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        discoverServices(g);
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        final String addr = address;
                        closeGatt();
                        reportDisconnected();
                    }
                });
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, int status) {
            final int st = status;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    onServices(g, st);
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor == null || !HeartRateParser.CCCD_UUID.equals(descriptor.getUuid())) {
                return;
            }
            final int st = status;
            final BluetoothGattDescriptor desc = descriptor;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    BluetoothGatt gg = gatt;
                    if (gg == null) {
                        return;
                    }
                    if (st == BluetoothGatt.GATT_SUCCESS) {
                        ready = true;
                        postMainReady();
                    } else if (cccdRetry < 2) {
                        cccdRetry++;
                        writeCccdLater(gg, desc, 400);
                    } else {
                        postMainError(address, "手表拒绝订阅心率通知（CCCD 写入失败）");
                        closeGatt();
                        reportDisconnected();
                    }
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null || !HeartRateParser.MEASUREMENT_UUID.equals(characteristic.getUuid())) {
                return;
            }
            byte[] data;
            try {
                data = characteristic.getValue();
            } catch (Exception e) {
                data = null;
            }
            if (data == null) {
                return;
            }
            HeartRateParser.Reading reading = HeartRateParser.parseMeasurement(data);
            if (reading == null || reading.bpm <= 0) {
                return;
            }
            final int bpm = reading.bpm;
            postMainHeartRate(bpm);
        }
    };

    private void discoverServices(BluetoothGatt g) {
        boolean ok;
        try {
            ok = g.discoverServices();
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            if (servicesRetry < 2) {
                servicesRetry++;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        BluetoothGatt gg = gatt;
                        if (gg != null) {
                            discoverServices(gg);
                        }
                    }
                }, 300);
            } else {
                postMainError(address, "无法发现 GATT 服务");
                closeGatt();
                reportDisconnected();
            }
        }
    }

    private void onServices(BluetoothGatt g, int status) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            postMainError(address, "服务发现失败（" + status + "）");
            closeGatt();
            reportDisconnected();
            return;
        }

        BluetoothGattService service = g.getService(HeartRateParser.SERVICE_UUID);
        if (service == null) {
            postMainError(address, "该设备没有心率服务 0x180D（手表需开启心率广播）");
            closeGatt();
            reportDisconnected();
            return;
        }

        BluetoothGattCharacteristic measurement = service.getCharacteristic(HeartRateParser.MEASUREMENT_UUID);
        if (measurement == null) {
            postMainError(address, "该设备没有心率测量特征 0x2A37");
            closeGatt();
            reportDisconnected();
            return;
        }

        boolean notified;
        try {
            notified = g.setCharacteristicNotification(measurement, true);
        } catch (Exception e) {
            notified = false;
        }
        if (!notified) {
            postMainError(address, "无法开启本地通知（setCharacteristicNotification 失败）");
            closeGatt();
            reportDisconnected();
            return;
        }

        BluetoothGattDescriptor cccd = measurement.getDescriptor(HeartRateParser.CCCD_UUID);
        if (cccd == null && measurement.getDescriptors() != null) {
            for (int i = 0; i < measurement.getDescriptors().size(); i++) {
                BluetoothGattDescriptor d = measurement.getDescriptors().get(i);
                if (d != null && HeartRateParser.CCCD_UUID.equals(d.getUuid())) {
                    cccd = d;
                    break;
                }
            }
        }
        if (cccd == null) {
            postMainError(address, "缺少通知配置描述符（CCCD），无法接收心率");
            closeGatt();
            reportDisconnected();
            return;
        }

        writeCccdLater(g, cccd, 120);
    }

    private void writeCccdLater(final BluetoothGatt g, final BluetoothGattDescriptor descriptor, long delayMs) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                writeCccd(g, descriptor);
            }
        }, delayMs);
    }

    private void writeCccd(BluetoothGatt g, BluetoothGattDescriptor descriptor) {
        boolean ok;
        try {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            ok = g.writeDescriptor(descriptor);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            if (cccdRetry < 2) {
                cccdRetry++;
                writeCccdLater(g, descriptor, 400);
            } else {
                postMainError(address, "订阅心率通知失败（手表无响应）");
                closeGatt();
                reportDisconnected();
            }
        }
    }

    // ---------------------------------------------------------------- 清理

    private void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        ready = false;
        if (g != null) {
            try {
                g.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void reportDisconnected() {
        if (disconnectReported) {
            return;
        }
        disconnectReported = true;
        final String addr = address;
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onDisconnected(addr);
                }
            }
        });
    }

    private void postMainLinkUp() {
        final String addr = address;
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onLinkUp(addr);
                }
            }
        });
    }

    private void postMainReady() {
        final String addr = address;
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onReady(addr);
                }
            }
        });
    }

    private void postMainHeartRate(final int bpm) {
        final String addr = address;
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onHeartRate(addr, bpm);
                }
            }
        });
    }

    private void postMainError(final String addr, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onError(addr, message);
                }
            }
        });
    }
}
