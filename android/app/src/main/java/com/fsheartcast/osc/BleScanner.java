package com.fsheartcast.osc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;

import java.util.List;

/** BLE 广播扫描封装（只负责扫描，过滤逻辑在 HeartRateCore 里）。 */
public final class BleScanner {

    public interface Callback {
        void onResult(ScanResult result);

        void onFailed(int errorCode);
    }

    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private Callback callback;
    private volatile boolean scanning;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            Callback cb = callback;
            if (cb != null && result != null) {
                cb.onResult(result);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            Callback cb = callback;
            if (cb == null || results == null) {
                return;
            }
            for (int i = 0; i < results.size(); i++) {
                ScanResult r = results.get(i);
                if (r != null) {
                    cb.onResult(r);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            Callback cb = callback;
            if (cb != null) {
                cb.onFailed(errorCode);
            }
        }
    };

    public BleScanner(BluetoothAdapter adapter) {
        this.adapter = adapter;
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean start(Callback cb) {
        if (scanning) {
            return true;
        }
        if (adapter == null || !adapter.isEnabled()) {
            return false;
        }
        callback = cb;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                return false;
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            return true;
        } catch (Exception e) {
            scanning = false;
            scanner = null;
            return false;
        }
    }

    public void stop() {
        BluetoothLeScanner s = scanner;
        scanner = null;
        scanning = false;
        callback = null;
        if (s != null) {
            try {
                s.stopScan(scanCallback);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
