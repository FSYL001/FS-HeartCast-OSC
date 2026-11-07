package com.fsheartcast.osc;

import java.util.UUID;

/**
 * 蓝牙标准心率协议解析。
 * 与桌面版 HOSC/Services/HeartRateParser.cs 等价：
 *  - 广播：AD Type 0x16（16-bit Service Data），前两字节为 0x180D（小端），其余为心率测量 payload
 *  - GATT：0x2A37 通知，flags bit0 决定 8 位还是 16 位 BPM
 */
public final class HeartRateParser {

    public static final UUID SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    public static final UUID MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private HeartRateParser() {
    }

    public static final class Reading {
        public final int bpm;
        public final boolean hasContactSupport;
        public final boolean contactDetected;
        public final boolean hasRrInterval;

        Reading(int bpm, boolean hasContactSupport, boolean contactDetected, boolean hasRrInterval) {
            this.bpm = bpm;
            this.hasContactSupport = hasContactSupport;
            this.contactDetected = contactDetected;
            this.hasRrInterval = hasRrInterval;
        }
    }

    /** 解析 0x2A37 心率测量特征值。 */
    public static Reading parseMeasurement(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        int flags = data[0] & 0xFF;
        boolean hasContactSupport = (flags & 0x04) != 0;
        boolean contactDetected = (flags & 0x08) != 0;
        boolean hasRr = (flags & 0x10) != 0;

        int bpm;
        if ((flags & 0x01) != 0) {
            if (data.length < 3) {
                return null;
            }
            bpm = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        } else {
            if (data.length < 2) {
                return null;
            }
            bpm = data[1] & 0xFF;
        }

        if (bpm <= 0 || bpm > 255) {
            return null;
        }
        return new Reading(bpm, hasContactSupport, contactDetected, hasRr);
    }

    /**
     * 解析广播里的 0x180D 服务数据。
     * Android 的 ScanRecord 有时候会把 16-bit 服务数据按键值归一化，这里直接按字节判断，
     * 与桌面版一致，避免依赖 ParcelUuid 的归一化细节。
     */
    public static Integer parseServiceData(byte[] serviceData) {
        if (serviceData == null || serviceData.length < 3) {
            return null;
        }
        int uuid = (serviceData[0] & 0xFF) | ((serviceData[1] & 0xFF) << 8);
        if (uuid != 0x180D) {
            return null;
        }
        byte[] payload = new byte[serviceData.length - 2];
        System.arraycopy(serviceData, 2, payload, 0, payload.length);
        Reading reading = parseMeasurement(payload);
        return reading == null ? null : Integer.valueOf(reading.bpm);
    }

    /** 判定一段广播 service data 是否属于心率服务（不带 payload 解析）。 */
    public static boolean isHeartRateServiceData(byte[] serviceData) {
        if (serviceData == null || serviceData.length < 2) {
            return false;
        }
        return (serviceData[0] & 0xFF) == 0x0D && (serviceData[1] & 0xFF) == 0x18;
    }
}
