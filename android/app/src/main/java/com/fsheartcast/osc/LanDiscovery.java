package com.fsheartcast.osc;

import android.os.Handler;
import android.os.Looper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 局域网里找电脑上的 OSC 中继：手机广播一个探测包，relay 会回 "FSHEARTCAST-RELAY <端口>"，
 * 于是不用手输 IP 也能定位到这台电脑。
 *
 * 探测同时发往 9001（中继默认端口）和 9000（VRChat 本机端口）；
 * 只有中继会回复，VRChat 本身不回包，所以「找到」意味着中继在跑 —— 这也正是能收到 OSC 的前提。
 */
public final class LanDiscovery {

    private static final String PROBE = "FSHEARTCAST/1";
    private static final String REPLY_PREFIX = "FSHEARTCAST-RELAY";
    private static final int LISTEN_MS = 1600;
    private static final int[] PORTS = { 9001, 9000 };

    private LanDiscovery() {
    }

    public static final class Result {
        public final String host;
        public final int port;

        Result(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public interface Callback {
        void onFinished(Result result);
    }

    public static void scan(final Callback callback) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Result result = null;
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket();
                    socket.setBroadcast(true);
                    socket.setSoTimeout(150);

                    byte[] probe = PROBE.getBytes("UTF-8");
                    List<InetAddress> targets = collectTargets();
                    for (int i = 0; i < targets.size(); i++) {
                        for (int p = 0; p < PORTS.length; p++) {
                            try {
                                socket.send(new DatagramPacket(probe, probe.length, targets.get(i), PORTS[p]));
                            } catch (Exception ignored) {
                                // 单个目标失败不影响整体扫描
                            }
                        }
                    }

                    long deadline = System.currentTimeMillis() + LISTEN_MS;
                    byte[] buffer = new byte[96];
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                            socket.receive(response);
                            String text = new String(response.getData(), 0, response.getLength(), "UTF-8");
                            if (text.startsWith(REPLY_PREFIX)) {
                                result = new Result(response.getAddress().getHostAddress(), replyPort(text));
                                break;
                            }
                        } catch (java.net.SocketTimeoutException ignored) {
                            // 继续等
                        } catch (Exception ignored) {
                            // ignore
                        }
                    }
                } catch (Exception ignored) {
                    // 扫描失败按“没找到”处理
                } finally {
                    if (socket != null) {
                        socket.close();
                    }
                }

                final Result finalResult = result;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onFinished(finalResult);
                    }
                });
            }
        }, "lan-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    /** 解析 "FSHEARTCAST-RELAY 9001" 里的端口，异常时回退 9001。 */
    private static int replyPort(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length >= 2) {
            try {
                int port = Integer.parseInt(parts[1].trim());
                if (port >= 1 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException ignored) {
                // fallthrough
            }
        }
        return 9001;
    }

    /** 本机所在 /24 网段的所有主机 + 接口广播地址。 */
    private static List<InetAddress> collectTargets() {
        List<InetAddress> targets = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    if (!(address instanceof java.net.Inet4Address)) {
                        continue;
                    }
                    byte[] raw = address.getAddress();
                    if (raw.length != 4) {
                        continue;
                    }
                    int a = raw[0] & 0xFF;
                    int b = raw[1] & 0xFF;
                    int c = raw[2] & 0xFF;
                    int self = raw[3] & 0xFF;
                    if (a == 127) {
                        continue;
                    }
                    if (interfaceAddress.getBroadcast() != null) {
                        targets.add(interfaceAddress.getBroadcast());
                    }
                    for (int host = 1; host <= 254; host++) {
                        if (host == self) {
                            continue;
                        }
                        try {
                            targets.add(InetAddress.getByAddress(new byte[] {
                                    (byte) a, (byte) b, (byte) c, (byte) host
                            }));
                        } catch (Exception ignored) {
                            // ignore
                        }
                    }
                }
            }
            try {
                targets.add(InetAddress.getByAddress(new byte[] {
                        (byte) 255, (byte) 255, (byte) 255, (byte) 255
                }));
            } catch (Exception ignored) {
                // ignore
            }
        } catch (Exception ignored) {
            // 枚举失败时返回空列表
        }
        return targets;
    }
}
