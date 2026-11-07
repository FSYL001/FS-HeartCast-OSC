package com.fsheartcast.osc;

import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * OSC 1.0 over UDP。参数清单见 OscParams，编码见 OscMessage。
 * 行为与桌面版一致：400ms 节流、心跳参数 120ms 后复位、每次心跳 13 个参数 + 1 个复位。
 *
 * 所有网络操作都在单独的 "osc-tx" / "osc-timer" 线程上执行，不阻塞 UI。
 */
public final class OscUdpClient {

    private static final long THROTTLE_MS = 400;
    private static final long BEAT_RESET_MS = 120;

    private final ScheduledExecutorService tx;
    private final ScheduledExecutorService timer;

    private DatagramSocket socket;
    private InetAddress target;
    private String targetHost = "";
    private int targetPort = 9000;

    private long lastSendAt;
    private boolean beatToggle;
    private ScheduledFuture<?> beatResetFuture;

    private volatile boolean closed;
    private volatile long packetsSent;
    private volatile int lastSentBpm;
    private volatile String lastError = "";

    public OscUdpClient() {
        ThreadFactory ioFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "osc-tx");
                t.setDaemon(true);
                return t;
            }
        };
        ThreadFactory timerFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "osc-timer");
                t.setDaemon(true);
                return t;
            }
        };
        tx = Executors.newSingleThreadScheduledExecutor(ioFactory);
        timer = Executors.newSingleThreadScheduledExecutor(timerFactory);
    }

    // ---------------------------------------------------------------- 配置

    public void setTarget(final String host, final int port) {
        if (closed) {
            return;
        }
        tx.execute(new Runnable() {
            @Override
            public void run() {
                String h = host == null ? "" : host.trim();
                if (!h.equals(targetHost) || port != targetPort) {
                    targetHost = h;
                    targetPort = port;
                    target = null;
                    closeSocket();
                }
            }
        });
    }

    public String getTargetText() {
        return targetHost + ":" + targetPort;
    }

    public long getPacketsSent() {
        return packetsSent;
    }

    public int getLastSentBpm() {
        return lastSentBpm;
    }

    public String getLastError() {
        return lastError;
    }

    // ---------------------------------------------------------------- 发送

    /** 一次完整的心率推送（含所有兼容参数），内部有 400ms 节流。 */
    public void postHeartRate(final int bpm, final int averageBpm,
                              final String hrAddress, final String beatAddress) {
        if (closed || bpm <= 0) {
            return;
        }
        tx.execute(new Runnable() {
            @Override
            public void run() {
                doHeartRate(bpm, averageBpm, hrAddress, beatAddress);
            }
        });
    }

    /** 连接状态四参数。 */
    public void postConnected(final boolean connected) {
        if (closed) {
            return;
        }
        tx.execute(new Runnable() {
            @Override
            public void run() {
                List<OscParams.Param> params = OscParams.connected(connected);
                for (int i = 0; i < params.size(); i++) {
                    OscParams.Param p = params.get(i);
                    send(p.address, new Object[] { p.value });
                }
            }
        });
    }

    /** OSC 测试：72 BPM + 聊天框提示。会重置节流，保证测试真的发得出去。 */
    public void postTest(final String hrAddress, final String beatAddress) {
        if (closed) {
            return;
        }
        tx.execute(new Runnable() {
            @Override
            public void run() {
                lastSendAt = 0;
                doHeartRate(72, 72, hrAddress, beatAddress);
                send("/chatbox/input", new Object[] { "FS-HeartCast OSC (Android) 测试 72 BPM", Boolean.TRUE });
                timer.schedule(new Runnable() {
                    @Override
                    public void run() {
                        send("/chatbox/input", new Object[] { "", Boolean.FALSE });
                    }
                }, 2500, TimeUnit.MILLISECONDS);
            }
        });
    }

    public void close() {
        closed = true;
        if (beatResetFuture != null) {
            beatResetFuture.cancel(false);
            beatResetFuture = null;
        }
        tx.execute(new Runnable() {
            @Override
            public void run() {
                closeSocket();
            }
        });
        tx.shutdown();
        timer.shutdownNow();
    }

    // ---------------------------------------------------------------- 内部

    private void doHeartRate(int bpm, int averageBpm, String hrAddress, String beatAddress) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSendAt < THROTTLE_MS) {
            return;
        }
        lastSendAt = now;
        beatToggle = !beatToggle;

        List<OscParams.Param> params =
                OscParams.heartRate(bpm, averageBpm, beatToggle, hrAddress, beatAddress);
        for (int i = 0; i < params.size(); i++) {
            OscParams.Param p = params.get(i);
            send(p.address, new Object[] { p.value });
        }

        lastSentBpm = bpm;
        scheduleBeatReset(OscParams.normalize(beatAddress, Prefs.DEFAULT_BEAT));
    }

    private void scheduleBeatReset(final String beatAddress) {
        if (beatResetFuture != null) {
            beatResetFuture.cancel(false);
        }
        beatResetFuture = timer.schedule(new Runnable() {
            @Override
            public void run() {
                send(beatAddress, new Object[] { Integer.valueOf(0) });
            }
        }, BEAT_RESET_MS, TimeUnit.MILLISECONDS);
    }

    private boolean ensureReady() {
        try {
            if (closed) {
                return false;
            }
            if (targetHost.length() == 0) {
                lastError = "未填写电脑 IP";
                return false;
            }
            if (target == null) {
                target = InetAddress.getByName(targetHost);
            }
            if (socket == null || socket.isClosed()) {
                socket = new DatagramSocket();
            }
            return true;
        } catch (Exception e) {
            lastError = "无法解析电脑地址：" + e.getClass().getSimpleName();
            target = null;
            closeSocket();
            return false;
        }
    }

    private boolean send(String oscAddress, Object[] values) {
        if (!ensureReady()) {
            return false;
        }
        try {
            byte[] packet = OscMessage.build(oscAddress, values);
            DatagramPacket dp = new DatagramPacket(packet, packet.length, target, targetPort);
            socket.send(dp);
            packetsSent++;
            lastError = "";
            return true;
        } catch (Exception e) {
            lastError = "OSC 发送失败：" + e.getClass().getSimpleName();
            closeSocket();
            return false;
        }
    }

    private void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // ignore
            }
            socket = null;
        }
    }
}
