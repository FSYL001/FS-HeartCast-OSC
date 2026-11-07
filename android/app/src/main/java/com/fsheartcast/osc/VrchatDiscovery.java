package com.fsheartcast.osc;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;

/**
 * 用 mDNS（Android 自带 NsdManager）发现局域网里正在运行的 VRChat。
 *
 * VRChat 开启 OSC 后会广播两个 DNS-SD 服务：
 *   _osc._udp    → 端口就是 OSC 端口（默认 9000），直接可用
 *   _oscjson._tcp → OSCQuery，端口是随机的 HTTP 端口，只用来确认主机
 * 所以手机端不需要在电脑上装任何东西，也不需要中继就能找到地址。
 */
public final class VrchatDiscovery {

    private static final long TIMEOUT_MS = 6000L;

    private VrchatDiscovery() {
    }

    public static final class Result {
        public final String host;
        public final int port;
        public final String serviceName;

        Result(String host, int port, String serviceName) {
            this.host = host;
            this.port = port;
            this.serviceName = serviceName;
        }
    }

    public interface Callback {
        void onFinished(Result result);
    }

    public static void scan(final Context context, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final NsdManager nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    callback.onFinished(null);
                }
            });
            return;
        }

        WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicastLock = null;
        try {
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("fs-heartcast-mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
            // 没有锁也继续尝试
        }

        final DiscoverySession session = new DiscoverySession(appContext, nsdManager, main,
                callback, multicastLock);
        session.start();
    }

    /** 管理一次发现过程：多类型、单次回调、超时、清理。 */
    private static final class DiscoverySession {

        private static final String TYPE_OSC = "_osc._udp";
        private static final String TYPE_OSCJSON = "_oscjson._tcp";

        private final Context context;
        private final NsdManager nsdManager;
        private final Handler main;
        private final Callback callback;
        private final WifiManager.MulticastLock multicastLock;

        private boolean resolved;
        private boolean resolving;
        private boolean stopped;

        private final NsdManager.DiscoveryListener oscListener;
        private final NsdManager.DiscoveryListener oscJsonListener;
        private final Runnable timeoutRunnable;

        DiscoverySession(Context context, NsdManager nsdManager, Handler main,
                         Callback callback, WifiManager.MulticastLock multicastLock) {
            this.context = context;
            this.nsdManager = nsdManager;
            this.main = main;
            this.callback = callback;
            this.multicastLock = multicastLock;

            oscListener = createListener(true);
            oscJsonListener = createListener(false);
            timeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    finish(null);
                }
            };
        }

        void start() {
            try {
                nsdManager.discoverServices(TYPE_OSC, NsdManager.PROTOCOL_DNS_SD, oscListener);
            } catch (Exception ignored) {
                // 某些设备对 _osc._udp 不友好，继续尝试 OSCQuery
            }
            try {
                nsdManager.discoverServices(TYPE_OSCJSON, NsdManager.PROTOCOL_DNS_SD, oscJsonListener);
            } catch (Exception ignored) {
                // ignore
            }
            main.postDelayed(timeoutRunnable, TIMEOUT_MS);
        }

        private NsdManager.DiscoveryListener createListener(final boolean isOsc) {
            return new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    // 继续等另一个类型
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    // ignore
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {
                    // ignore
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    // ignore
                }

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    resolve(serviceInfo, isOsc);
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                    // ignore
                }
            };
        }

        private void resolve(final NsdServiceInfo serviceInfo, final boolean isOsc) {
            if (resolved || resolving || stopped) {
                return;
            }
            resolving = true;
            try {
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        resolving = false;
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        resolving = false;
                        if (resolved || stopped) {
                            return;
                        }
                        String host = hostOf(info);
                        if (host == null || host.length() == 0) {
                            return;
                        }
                        int port = info.getPort();
                        if (!isOsc) {
                            // OSCQuery 的端口是随机 HTTP 端口，OSC 仍走默认值
                            port = Prefs.DEFAULT_PORT;
                        }
                        if (port <= 0) {
                            port = Prefs.DEFAULT_PORT;
                        }
                        finish(new Result(host, port, info.getServiceName()));
                    }
                });
            } catch (Exception e) {
                resolving = false;
            }
        }

        private String hostOf(NsdServiceInfo info) {
            try {
                InetAddress address = info.getHost();
                if (address != null && address.getHostAddress() != null) {
                    return address.getHostAddress();
                }
            } catch (Exception ignored) {
                // 个别机型 getHost() 返回 null
            }
            try {
                java.util.List<InetAddress> addresses = info.getHostAddresses();
                if (addresses != null && !addresses.isEmpty() && addresses.get(0) != null) {
                    return addresses.get(0).getHostAddress();
                }
            } catch (Exception ignored) {
                // ignore
            }
            return null;
        }

        private void finish(final Result result) {
            if (stopped) {
                return;
            }
            stopped = true;
            if (result != null) {
                resolved = true;
            }
            main.removeCallbacks(timeoutRunnable);
            try {
                nsdManager.stopServiceDiscovery(oscListener);
            } catch (Exception ignored) {
                // ignore
            }
            try {
                nsdManager.stopServiceDiscovery(oscJsonListener);
            } catch (Exception ignored) {
                // ignore
            }
            if (multicastLock != null && multicastLock.isHeld()) {
                try {
                    multicastLock.release();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            main.post(new Runnable() {
                @Override
                public void run() {
                    callback.onFinished(result);
                }
            });
        }
    }
}
