package com.fsheartcast.osc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 应用核心：把「BLE 扫描 / GATT 连接 → 心率解析 → OSC(UDP) 发送」串起来。
 * 单例，所有回调都在主线程。
 *
 * 数据源优先级：
 *   1. GATT 0x2A37 通知（连上后使用）
 *   2. 广播里的 0x180D Service Data（没连上时作为 fallback，不会因为连上而冻结）
 */
public final class HeartRateCore {

    public interface Listener {
        void onSnapshot(Snapshot snapshot);

        void onDevicesChanged();

        void onLog(String line);
    }

    /** 给 UI 的只读快照。 */
    public static final class Snapshot {
        public final boolean scanning;
        public final String scanError;
        public final String activeAddress;
        public final String activeName;
        public final boolean connected;
        public final boolean connecting;
        public final int bpm;
        public final boolean oscEnabled;
        public final String target;
        public final int lastSentBpm;
        public final long packetsSent;
        public final String lastError;
        public final int deviceCount;

        Snapshot(boolean scanning, String scanError, String activeAddress, String activeName,
                 boolean connected, boolean connecting, int bpm, boolean oscEnabled, String target,
                 int lastSentBpm, long packetsSent, String lastError, int deviceCount) {
            this.scanning = scanning;
            this.scanError = scanError;
            this.activeAddress = activeAddress;
            this.activeName = activeName;
            this.connected = connected;
            this.connecting = connecting;
            this.bpm = bpm;
            this.oscEnabled = oscEnabled;
            this.target = target;
            this.lastSentBpm = lastSentBpm;
            this.packetsSent = packetsSent;
            this.lastError = lastError;
            this.deviceCount = deviceCount;
        }
    }

    private static final long OFFLINE_MS = 12000;
    private static final long STALE_BPM_MS = 12000;
    private static final long CONNECT_RETRY_MS = 8000;
    private static final long GATT_WATCHDOG_MS = 15000;
    private static final long AVERAGE_WINDOW_MS = 20000;

    private static HeartRateCore instance;

    public static synchronized HeartRateCore get(Context context) {
        if (instance == null) {
            instance = new HeartRateCore(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, DeviceEntry> devices = new LinkedHashMap<String, DeviceEntry>();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final List<long[]> history = new ArrayList<long[]>();
    private final HeartRateGatt gatt;
    private final BleScanner scanner;
    private final OscUdpClient osc;
    private final BluetoothAdapter adapter;

    private String activeAddress;
    private boolean scanning;
    private String scanError = "";
    private boolean connecting;
    private boolean connected;
    private String connectedAddress;
    private int currentBpm;
    private long currentBpmAt;
    private long lastConnectAttempt;
    private String lastError = "";
    private boolean uiPending;

    private String targetHost = "";
    private int targetPort = Prefs.DEFAULT_PORT;
    private String hrAddress = Prefs.DEFAULT_HR;
    private String beatAddress = Prefs.DEFAULT_BEAT;
    private boolean oscEnabled = true;
    private boolean autoConnect = true;

    private HeartRateCore(Context context) {
        this.context = context;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
        this.gatt = new HeartRateGatt(context);
        this.scanner = new BleScanner(adapter);
        this.osc = new OscUdpClient();
        this.gatt.setListener(gattListener);
        loadSettings();
        main.postDelayed(tickRunnable, 1000);
    }

    // ---------------------------------------------------------------- 对外 API

    public void addListener(Listener listener) {
        if (listener == null || listeners.contains(listener)) {
            return;
        }
        listeners.add(listener);
        publish();
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void loadSettings() {
        oscEnabled = Prefs.oscEnabled(context);
        targetHost = Prefs.ip(context);
        targetPort = Prefs.port(context);
        hrAddress = Prefs.hrAddress(context);
        beatAddress = Prefs.beatAddress(context);
        autoConnect = Prefs.autoConnect(context);
        osc.setTarget(targetHost, targetPort);
        publish();
    }

    public List<DeviceEntry> deviceList() {
        return new ArrayList<DeviceEntry>(devices.values());
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isOscEnabled() {
        return oscEnabled;
    }

    public String getActiveAddress() {
        return activeAddress;
    }

    public boolean startScan() {
        if (scanning) {
            return true;
        }
        if (adapter == null) {
            scanError = "此设备不支持蓝牙";
            publish();
            return false;
        }
        boolean enabled;
        try {
            enabled = adapter.isEnabled();
        } catch (Exception e) {
            enabled = false;
        }
        if (!enabled) {
            scanError = "蓝牙未开启，请先打开蓝牙";
            publish();
            return false;
        }
        boolean ok = scanner.start(scanCallback);
        if (!ok) {
            scanError = "无法启动扫描（请检查蓝牙与权限）";
            publish();
            return false;
        }
        scanning = true;
        scanError = "";
        log("开始扫描附近的心率设备");
        publish();
        return true;
    }

    public void stopScan() {
        scanner.stop();
        if (!scanning) {
            publish();
            return;
        }
        scanning = false;
        log("停止扫描");
        publish();
    }

    public void selectDevice(String address) {
        DeviceEntry entry = devices.get(address);
        activeAddress = address;
        if (entry != null) {
            log("选中 " + entry.displayName(context));
        }
        postUiUpdate();
    }

    public void connectActive() {
        if (activeAddress == null) {
            lastError = "请先在列表里选一个设备";
            log(lastError);
            publish();
            return;
        }
        connect(activeAddress);
    }

    public void disconnect() {
        gatt.disconnect();
        if (connected || connecting) {
            sendConnectedState(false);
        }
        connected = false;
        connecting = false;
        connectedAddress = null;
        DeviceEntry entry = activeAddress == null ? null : devices.get(activeAddress);
        if (entry != null && entry.linkState != DeviceEntry.LINK_NONE) {
            entry.linkState = DeviceEntry.LINK_NONE;
            entry.errorText = "";
        }
        log("已断开连接");
        publish();
    }

    public void clearDevices() {
        gatt.disconnect();
        connected = false;
        connecting = false;
        connectedAddress = null;
        activeAddress = null;
        devices.clear();
        log("已清空设备列表");
        notifyDevices();
        publish();
    }

    public void setOscEnabled(boolean enabled) {
        oscEnabled = enabled;
        Prefs.setOscEnabled(context, enabled);
        if (enabled) {
            log("OSC 发送已开启 → " + targetHost + ":" + targetPort);
            if (connected) {
                sendConnectedState(true);
            }
        } else {
            log("OSC 发送已关闭");
            osc.postConnected(false);
        }
        publish();
    }

    public void applyTarget(String host, int port) {
        String h = host == null ? "" : host.trim();
        if (!Prefs.isValidHost(h)) {
            lastError = "电脑 IP 不合法";
            log(lastError);
            publish();
            return;
        }
        targetHost = h;
        targetPort = port;
        Prefs.setIp(context, h);
        Prefs.setPort(context, port);
        osc.setTarget(h, port);
        lastError = "";
        log("OSC 目标已设为 " + h + ":" + port);
        if (oscEnabled && connected) {
            sendConnectedState(true);
        }
        publish();
    }

    public boolean sendTest() {
        if (!Prefs.isValidHost(targetHost)) {
            lastError = "请先填写电脑 IP";
            log(lastError);
            publish();
            return false;
        }
        osc.postTest(hrAddress, beatAddress);
        log("已发送测试数据 72 BPM → " + targetHost + ":" + targetPort);
        publish();
        return true;
    }

    // ---------------------------------------------------------------- 扫描

    private final BleScanner.Callback scanCallback = new BleScanner.Callback() {
        @Override
        public void onResult(ScanResult result) {
            final ScanResult r = result;
            main.post(new Runnable() {
                @Override
                public void run() {
                    handleResult(r);
                }
            });
        }

        @Override
        public void onFailed(int errorCode) {
            final int code = errorCode;
            main.post(new Runnable() {
                @Override
                public void run() {
                    scanning = false;
                    scanError = "扫描失败（错误码 " + code + "）";
                    log(scanError);
                    publish();
                }
            });
        }
    };

    private void handleResult(ScanResult result) {
        BluetoothDevice device;
        try {
            device = result.getDevice();
        } catch (Exception e) {
            return;
        }
        if (device == null) {
            return;
        }

        String address;
        try {
            address = device.getAddress();
        } catch (Exception e) {
            return;
        }
        if (address == null || address.length() == 0) {
            return;
        }

        ScanRecord record = result.getScanRecord();
        String name = advertisementName(record);
        byte[] serviceData = heartRateServiceData(record);
        Integer advertisedBpm = HeartRateParser.parseServiceData(serviceData);
        boolean heartRateUuid = hasHeartRateService(record);
        boolean hasHr = heartRateUuid || serviceData != null;

        if (!isRelevant(name, hasHr)) {
            return;
        }

        DeviceEntry entry = devices.get(address);
        boolean isNew = false;
        if (entry == null) {
            entry = new DeviceEntry(address);
            devices.put(address, entry);
            isNew = true;
        }

        entry.markSeen(name, advertisedBpm == null ? 0 : advertisedBpm.intValue(),
                result.getRssi(), SystemClock.elapsedRealtime(), hasHr);

        if (isNew) {
            log("发现设备 " + entry.displayName(context) + "（" + address + "）");
            notifyDevices();
        }

        // 广播里直接带 BPM：GATT 未就绪时作为数据源
        boolean gattSource = connected && address.equals(connectedAddress);
        if (advertisedBpm != null && advertisedBpm.intValue() > 0 && !gattSource) {
            onBpm(advertisedBpm.intValue());
        }

        if (autoConnect && activeAddress == null) {
            activeAddress = address;
        }

        if (autoConnect && address.equals(activeAddress) && !connected && !connecting && hasHr) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastConnectAttempt > CONNECT_RETRY_MS) {
                connect(address);
            }
        }

        postUiUpdate();
    }

    private String advertisementName(ScanRecord record) {
        String name = null;
        if (record != null) {
            name = record.getDeviceName();
        }
        if (name != null && name.length() > 0) {
            return name.trim();
        }
        return "";
    }

    private byte[] heartRateServiceData(ScanRecord record) {
        if (record == null) {
            return null;
        }
        Map<ParcelUuid, byte[]> data;
        try {
            data = record.getServiceData();
        } catch (Exception e) {
            return null;
        }
        if (data == null) {
            return null;
        }
        for (Map.Entry<ParcelUuid, byte[]> entry : data.entrySet()) {
            byte[] value = entry.getValue();
            if (HeartRateParser.isHeartRateServiceData(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean hasHeartRateService(ScanRecord record) {
        if (record == null) {
            return false;
        }
        List<ParcelUuid> uuids;
        try {
            uuids = record.getServiceUuids();
        } catch (Exception e) {
            return false;
        }
        if (uuids == null) {
            return false;
        }
        for (int i = 0; i < uuids.size(); i++) {
            ParcelUuid pu = uuids.get(i);
            if (pu != null && HeartRateParser.SERVICE_UUID.equals(pu.getUuid())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRelevant(String name, boolean hasHeartRateService) {
        if (hasHeartRateService) {
            return true;
        }
        if (name == null || name.length() == 0) {
            return false;
        }
        String upper = name.toUpperCase(Locale.US);
        if (isAudioDevice(upper)) {
            return false;
        }
        return looksLikeHeartRateDevice(upper);
    }

    private static boolean isAudioDevice(String upperName) {
        String[] tokens = { "FREEBUDS", "HEADSET", "HEADPHONE", "EARBUD", "SPEAKER", "AUDIO", "SOUNDCORE" };
        for (int i = 0; i < tokens.length; i++) {
            if (upperName.indexOf(tokens[i]) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeHeartRateDevice(String upperName) {
        String[] tokens = { "WATCH", "BAND", "HUAWEI", "HONOR", "XIAOMI", "MI BAND", "MIBAND",
                "AMAZFIT", "HEART", "GT2", "GT3", "GT4", "GT5", "FIT", "REDMI" };
        for (int i = 0; i < tokens.length; i++) {
            if (upperName.indexOf(tokens[i]) >= 0) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- GATT

    private final HeartRateGatt.Listener gattListener = new HeartRateGatt.Listener() {
        @Override
        public void onLinkUp(String address) {
            log("蓝牙链路已建立，正在订阅心率通知");
        }

        @Override
        public void onReady(String address) {
            connecting = false;
            connected = true;
            connectedAddress = address;
            activeAddress = address;
            DeviceEntry entry = devices.get(address);
            if (entry != null) {
                entry.linkState = DeviceEntry.LINK_CONNECTED;
                entry.errorText = "";
            }
            log("已连接，开始接收心率");
            sendConnectedState(true);
            notifyDevices();
            publish();
        }

        @Override
        public void onHeartRate(String address, int bpm) {
            connectedAddress = address;
            onBpm(bpm);
        }

        @Override
        public void onDisconnected(String address) {
            boolean wasConnected = connected;
            connecting = false;
            connected = false;
            connectedAddress = null;
            DeviceEntry entry = devices.get(address);
            if (entry != null && entry.linkState != DeviceEntry.LINK_ERROR) {
                entry.linkState = DeviceEntry.LINK_NONE;
            }
            if (wasConnected) {
                sendConnectedState(false);
                log("连接已断开");
            }
            notifyDevices();
            publish();
        }

        @Override
        public void onError(String address, String message) {
            lastError = message == null ? "" : message;
            DeviceEntry entry = devices.get(address);
            if (entry != null) {
                entry.linkState = DeviceEntry.LINK_ERROR;
                entry.errorText = lastError;
            }
            log(lastError);
            notifyDevices();
            publish();
        }
    };

    private void connect(String address) {
        DeviceEntry entry = devices.get(address);
        if (entry == null) {
            return;
        }
        if (adapter == null) {
            lastError = "此设备不支持蓝牙";
            publish();
            return;
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (Exception e) {
            lastError = "设备地址无效";
            publish();
            return;
        }
        lastConnectAttempt = SystemClock.elapsedRealtime();
        connecting = true;
        connected = false;
        connectedAddress = null;
        entry.linkState = DeviceEntry.LINK_CONNECTING;
        entry.errorText = "";
        log("正在连接 " + entry.displayName(context));
        gatt.connect(device, address);
        notifyDevices();
        publish();
    }

    // ---------------------------------------------------------------- 心率与 OSC

    private void onBpm(int bpm) {
        if (bpm <= 0) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        currentBpm = bpm;
        currentBpmAt = now;
        rememberForAverage(bpm, now);

        if (oscEnabled) {
            osc.postHeartRate(bpm, averageBpm(), hrAddress, beatAddress);
        }
        postUiUpdate();
    }

    private void rememberForAverage(int bpm, long now) {
        history.add(new long[] { bpm, now });
        long cutoff = now - AVERAGE_WINDOW_MS;
        while (!history.isEmpty() && history.get(0)[1] < cutoff) {
            history.remove(0);
        }
        while (history.size() > 64) {
            history.remove(0);
        }
    }

    private int averageBpm() {
        if (history.isEmpty()) {
            return currentBpm;
        }
        long sum = 0;
        int count = 0;
        for (int i = 0; i < history.size(); i++) {
            long[] h = history.get(i);
            if (h[0] > 0 && h[0] <= 300) {
                sum += h[0];
                count++;
            }
        }
        if (count == 0) {
            return currentBpm;
        }
        return (int) Math.round((double) sum / (double) count);
    }

    private void sendConnectedState(boolean state) {
        if (!oscEnabled) {
            return;
        }
        osc.postConnected(state);
    }

    // ---------------------------------------------------------------- 定时维护

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean devicesChanged = false;

            for (DeviceEntry entry : devices.values()) {
                if (entry.online && now - entry.lastSeenMs > OFFLINE_MS) {
                    entry.online = false;
                    devicesChanged = true;
                }
            }

            if (currentBpm > 0 && now - currentBpmAt > STALE_BPM_MS) {
                currentBpm = 0;
            }

            if (connected && now - currentBpmAt > GATT_WATCHDOG_MS
                    && now - lastConnectAttempt > GATT_WATCHDOG_MS
                    && connectedAddress != null) {
                log("心率通知超时，尝试重新连接");
                connect(connectedAddress);
            }

            if (devicesChanged) {
                notifyDevices();
            }
            publish();
            main.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- 通知

    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            uiPending = false;
            notifyDevices();
            publish();
        }
    };

    private void postUiUpdate() {
        if (uiPending) {
            return;
        }
        uiPending = true;
        main.postDelayed(uiUpdateRunnable, 180);
    }

    private void notifyDevices() {
        List<Listener> copy = new ArrayList<Listener>(listeners);
        for (int i = 0; i < copy.size(); i++) {
            try {
                copy.get(i).onDevicesChanged();
            } catch (Exception ignored) {
                // 不让 UI 异常影响采集
            }
        }
    }

    private void publish() {
        Snapshot snapshot = snapshot();
        List<Listener> copy = new ArrayList<Listener>(listeners);
        for (int i = 0; i < copy.size(); i++) {
            try {
                copy.get(i).onSnapshot(snapshot);
            } catch (Exception ignored) {
                // 不让 UI 异常影响采集
            }
        }
    }

    private void log(String line) {
        List<Listener> copy = new ArrayList<Listener>(listeners);
        for (int i = 0; i < copy.size(); i++) {
            try {
                copy.get(i).onLog(line);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private Snapshot snapshot() {
        DeviceEntry entry = activeAddress == null ? null : devices.get(activeAddress);
        String oscError = osc.getLastError();
        String error = oscError != null ? oscError : "";
        if (error.length() == 0 && !oscEnabled) {
            error = "";
        }
        if (error.length() == 0) {
            error = lastError;
        }
        return new Snapshot(scanning, scanError, activeAddress,
                entry == null ? "" : entry.displayName(context), connected, connecting,
                currentBpm, oscEnabled, targetHost + ":" + targetPort,
                osc.getLastSentBpm(), osc.getPacketsSent(), error, devices.size());
    }
}
