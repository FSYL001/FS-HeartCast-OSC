package com.fsheartcast.osc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * 前台服务：让扫描 / GATT / OSC 在锁屏或切到后台后继续工作。
 * 通知栏实时显示当前心率和推送目标（文案走多语言资源）。
 */
public final class HeartRateService extends Service {

    public static final String EXTRA_START_SCAN = "start_scan";

    private static final String CHANNEL_ID = "hr_foreground";
    private static final int NOTIFICATION_ID = 2001;
    private static final long NOTIFY_INTERVAL_MS = 1000;

    private HeartRateCore core;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private long lastNotifyAt;
    private boolean startScanRequested;

    private final HeartRateCore.Listener listener = new HeartRateCore.Listener() {
        @Override
        public void onSnapshot(HeartRateCore.Snapshot snapshot) {
            updateNotification(snapshot);
        }

        @Override
        public void onDevicesChanged() {
            // 通知栏不展示设备列表
        }

        @Override
        public void onLog(String line) {
            // 界面日志
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Lang.wrap(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(getApplicationContext());
        core = HeartRateCore.get(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();

        try {
            startForeground(NOTIFICATION_ID, buildNotification(
                    getString(R.string.notif_waiting), getString(R.string.status_idle)));
        } catch (Exception ignored) {
            // 有些系统限制前台服务时忽略，界面内仍可使用
        }

        core.addListener(listener);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FSHeartCast:ble");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_START_SCAN, false)) {
            startScanRequested = true;
            if (!core.isScanning()) {
                core.startScan();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (core != null) {
            core.removeListener(listener);
            core.stopScan();
            core.disconnect();
        }
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.app_title));
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void updateNotification(HeartRateCore.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        boolean active = snapshot.scanning || snapshot.connected;
        if (wakeLock != null) {
            if (active) {
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(60000);
                }
            } else if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastNotifyAt < NOTIFY_INTERVAL_MS) {
            return;
        }
        lastNotifyAt = now;

        String title = snapshot.bpm > 0
                ? getString(R.string.notif_bpm, Integer.valueOf(snapshot.bpm))
                : getString(R.string.notif_waiting);
        String text;
        if (snapshot.lastError != null && snapshot.lastError.length() > 0) {
            text = snapshot.lastError;
        } else {
            String prefix = snapshot.oscEnabled
                    ? snapshot.target
                    : getString(R.string.notif_osc_off);
            text = prefix + "  ·  " + getString(R.string.status_packets,
                    Integer.valueOf((int) snapshot.packetsSent));
        }

        if (notificationManager != null) {
            try {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(title, text));
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_hr)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent);
        return builder.build();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
