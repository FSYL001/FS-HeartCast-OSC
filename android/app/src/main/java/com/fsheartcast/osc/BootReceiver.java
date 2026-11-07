package com.fsheartcast.osc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：对应桌面版 StartupService（HKCU Run 键）。
 * Android 用 BOOT_COMPLETED 广播启动前台服务；部分系统会限制后台启动前台服务，
 * 失败时静默忽略，不影响手动打开 App。
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!Prefs.bootStart(context)) {
            return;
        }
        try {
            Intent service = new Intent(context, HeartRateService.class);
            service.putExtra(HeartRateService.EXTRA_START_SCAN, Prefs.autoScan(context));
            context.startForegroundService(service);
        } catch (Exception ignored) {
            // 系统限制时忽略
        }
    }
}
