package com.fsheartcast.osc;

import android.content.Context;

/** 设备列表里的一个条目（UI 模型）。文案走多语言资源，和桌面版 HeartRateDevice 对齐。 */
public final class DeviceEntry {

    public static final int LINK_NONE = 0;
    public static final int LINK_CONNECTING = 1;
    public static final int LINK_CONNECTED = 2;
    public static final int LINK_ERROR = 3;

    public final String address;
    public String name = "";
    public int bpm;
    public int rssi;
    public long lastSeenMs;
    public boolean hasHeartRateService;
    public boolean online;
    public int linkState = LINK_NONE;
    public String errorText = "";

    public DeviceEntry(String address) {
        this.address = address == null ? "" : address;
    }

    public void markSeen(String deviceName, int measuredBpm, int measuredRssi,
                         long nowMs, boolean heartRateService) {
        if (deviceName != null && deviceName.length() > 0) {
            name = deviceName;
        }
        if (measuredBpm > 0) {
            bpm = measuredBpm;
        }
        rssi = measuredRssi;
        lastSeenMs = nowMs;
        online = true;
        if (heartRateService) {
            hasHeartRateService = true;
        }
    }

    public String displayName(Context context) {
        if (name != null && name.length() > 0) {
            return name;
        }
        return context.getString(R.string.device_no_name);
    }

    public String statusText(Context context) {
        switch (linkState) {
            case LINK_CONNECTING:
                return context.getString(R.string.device_connecting);
            case LINK_CONNECTED:
                return context.getString(R.string.device_connected);
            case LINK_ERROR:
                return errorText.length() > 0 ? errorText : context.getString(R.string.device_connect_error);
            default:
                return context.getString(online ? R.string.device_broadcasting : R.string.device_offline);
        }
    }

    public String bpmText() {
        return bpm > 0 ? String.valueOf(bpm) : "--";
    }

    public String signalText() {
        return rssi + " dBm";
    }
}
