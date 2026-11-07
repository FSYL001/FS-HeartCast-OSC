package com.fsheartcast.osc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 JVM 验证：OSC 编码/解码 与 心率解析。
 * 不依赖 Android，用 tests/run-tests.ps1 运行。
 */
public final class TestMain {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testOscIntMessage();
        testOscBoolMessage();
        testOscFloatAndString();
        testOscRoundTrip();
        testHeartRateParamList();
        testConnectedParamList();
        testClampAndNormalize();
        testHeartRateMeasurement();
        testServiceData();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ OSC 编码

    private static void testOscIntMessage() {
        byte[] packet = OscMessage.build("/abc", new Object[] { Integer.valueOf(72) });
        byte[] expected = new byte[] {
                '/', 'a', 'b', 'c', 0, 0, 0, 0,
                ',', 'i', 0, 0,
                0, 0, 0, 72
        };
        assertBytes("/abc + int 72 的完整字节", expected, packet);
        assertEquals("/abc + int 72 的长度", Integer.valueOf(16), Integer.valueOf(packet.length));
    }

    private static void testOscBoolMessage() {
        byte[] packet = OscMessage.build("/x", new Object[] { Boolean.TRUE, Boolean.FALSE });
        byte[] expected = new byte[] {
                '/', 'x', 0, 0,
                ',', 'T', 'F', 0
        };
        assertBytes("/x + true,false 只用类型标签", expected, packet);
    }

    private static void testOscFloatAndString() {
        byte[] packet = OscMessage.build("/f", new Object[] { Float.valueOf(1.5f) });
        byte[] expected = new byte[] {
                '/', 'f', 0, 0,
                ',', 'f', 0, 0,
                (byte) 0x3F, (byte) 0xC0, 0, 0
        };
        assertBytes("/f + float 1.5 大端", expected, packet);

        byte[] stringPacket = OscMessage.build("/s", new Object[] { "hi" });
        byte[] stringExpected = new byte[] {
                '/', 's', 0, 0,
                ',', 's', 0, 0,
                'h', 'i', 0, 0
        };
        assertBytes("/s + string 4 字节对齐", stringExpected, stringPacket);
    }

    private static void testOscRoundTrip() {
        List<OscParams.Param> params = OscParams.heartRate(72, 80, true, null, null);
        for (int i = 0; i < params.size(); i++) {
            OscParams.Param p = params.get(i);
            byte[] packet = OscMessage.build(p.address, new Object[] { p.value });
            Decoded decoded = decode(packet);
            assertEquals("round-trip 地址 " + p.address, p.address, decoded.address);
            assertEquals("round-trip 值 " + p.address, p.value, decoded.values.get(0));
        }

        // 多参数一条消息
        byte[] multi = OscMessage.build("/m",
                new Object[] { Integer.valueOf(7), Float.valueOf(0.5f), Boolean.TRUE, "ok" });
        Decoded d = decode(multi);
        assertEquals("多参数地址", "/m", d.address);
        assertEquals("多参数量", Integer.valueOf(4), Integer.valueOf(d.values.size()));
        assertEquals("多参数 int", Integer.valueOf(7), d.values.get(0));
        assertEquals("多参数 float", Float.valueOf(0.5f), d.values.get(1));
        assertEquals("多参数 bool", Boolean.TRUE, d.values.get(2));
        assertEquals("多参数 string", "ok", d.values.get(3));
    }

    // ------------------------------------------------------------ 参数契约

    private static void testHeartRateParamList() {
        List<OscParams.Param> params = OscParams.heartRate(72, 80, true, null, null);
        assertEquals("心率参数数量(13)", Integer.valueOf(13), Integer.valueOf(params.size()));

        assertEquals("第1个地址", OscParams.DEFAULT_HR_ADDRESS, params.get(0).address);
        assertEquals("第1个值", Integer.valueOf(72), params.get(0).value);
        assertEquals("第2个地址", OscParams.DEFAULT_BEAT_ADDRESS, params.get(1).address);
        assertEquals("第2个值=1", Integer.valueOf(1), params.get(1).value);
        assertEquals("第3个地址", OscParams.HEART_BEAT_TOGGLE, params.get(2).address);
        assertEquals("第3个值=toggle", Boolean.TRUE, params.get(2).value);
        assertEquals("VRCOSC Value", Integer.valueOf(72), find(params, OscParams.VRCOSC_VALUE));
        assertEquals("VRCOSC Beat", Boolean.TRUE, find(params, OscParams.VRCOSC_BEAT));
        assertEquals("Normalised = 72/240", Float.valueOf(0.3f),
                find(params, OscParams.VRCOSC_NORMALISED));
        assertEquals("Average 用平滑值", Integer.valueOf(80), find(params, OscParams.VRCOSC_AVERAGE));
        assertEquals("HeartRateInt", Integer.valueOf(72), find(params, OscParams.HEART_RATE_INT));
        assertEquals("HeartRateFloat01", Float.valueOf(0.3f), find(params, OscParams.HEART_RATE_FLOAT01));
        assertEquals("HRPercent = 72/255", Float.valueOf(72f / 255f), find(params, OscParams.HR_PERCENT));
        assertEquals("HeartRate = 72/127.5-1", Float.valueOf(72f / 127.5f - 1f),
                find(params, OscParams.HEART_RATE));

        // 自定义地址生效
        List<OscParams.Param> custom = OscParams.heartRate(60, 0, false, "custom/hr", "/beat");
        assertEquals("自定义 HR 地址补前导斜杠", "/custom/hr", custom.get(0).address);
        assertEquals("自定义 Beat 地址", "/beat", custom.get(1).address);
        assertEquals("没有平均值时 Average 回退瞬时值", Integer.valueOf(60),
                find(custom, OscParams.VRCOSC_AVERAGE));

        // 极端值 clamp
        List<OscParams.Param> high = OscParams.heartRate(255, 255, false, null, null);
        assertEquals("高心率 Normalised clamp 到 1", Float.valueOf(1f),
                find(high, OscParams.VRCOSC_NORMALISED));
        assertEquals("高心率 HeartRate clamp 到 1", Float.valueOf(1f),
                find(high, OscParams.HEART_RATE));
    }

    private static void testConnectedParamList() {
        List<OscParams.Param> params = OscParams.connected(true);
        assertEquals("连接状态参数数量", Integer.valueOf(4), Integer.valueOf(params.size()));
        assertEquals("Connected", Boolean.TRUE, find(params, OscParams.VRCOSC_CONNECTED));
        assertEquals("Enabled", Boolean.TRUE, find(params, OscParams.VRCOSC_ENABLED));
        assertEquals("isHRConnected", Boolean.TRUE, find(params, OscParams.IS_HR_CONNECTED));
        assertEquals("isHRActive", Boolean.TRUE, find(params, OscParams.IS_HR_ACTIVE));

        List<OscParams.Param> off = OscParams.connected(false);
        assertEquals("断开状态", Boolean.FALSE, find(off, OscParams.VRCOSC_CONNECTED));
    }

    private static void testClampAndNormalize() {
        assertEquals("clamp 上限", Float.valueOf(1f), Float.valueOf(OscParams.clamp(1.7f, 0f, 1f)));
        assertEquals("clamp 下限", Float.valueOf(-1f), Float.valueOf(OscParams.clamp(-2f, -1f, 1f)));
        assertEquals("normalize 补斜杠", "/a/b", OscParams.normalize("a/b", "/x"));
        assertEquals("normalize 空值回退", "/x", OscParams.normalize("   ", "/x"));
        assertEquals("normalize null 回退", "/x", OscParams.normalize(null, "/x"));
    }

    // ------------------------------------------------------------ 心率解析

    private static void testHeartRateMeasurement() {
        // flags=0（8 位），BPM=72
        HeartRateParser.Reading r = HeartRateParser.parseMeasurement(new byte[] { 0x00, 0x48 });
        assertNotNull("8 位心率", r);
        assertEquals("8 位心率值", Integer.valueOf(72), Integer.valueOf(r.bpm));

        // flags=0x01（16 位），BPM=72
        r = HeartRateParser.parseMeasurement(new byte[] { 0x01, 0x48, 0x00 });
        assertNotNull("16 位心率", r);
        assertEquals("16 位心率值", Integer.valueOf(72), Integer.valueOf(r.bpm));

        // 16 位 300 BPM：协议允许，但本项目按桌面版规则拒绝 >255
        r = HeartRateParser.parseMeasurement(new byte[] { 0x01, 0x2C, 0x01 });
        assertNull("拒绝 >255 的 BPM", r);

        // 接触状态位
        r = HeartRateParser.parseMeasurement(new byte[] { (byte) 0x0C, 0x50 });
        assertNotNull("带接触位", r);
        assertEquals("接触位 bpm", Integer.valueOf(80), Integer.valueOf(r.bpm));
        assertTrue("contact 支持", r.hasContactSupport);
        assertTrue("contact 检出", r.contactDetected);

        // 异常输入
        assertNull("空数据", HeartRateParser.parseMeasurement(null));
        assertNull("空数组", HeartRateParser.parseMeasurement(new byte[0]));
        assertNull("只有 flags", HeartRateParser.parseMeasurement(new byte[] { 0x00 }));
        assertNull("BPM=0", HeartRateParser.parseMeasurement(new byte[] { 0x00, 0x00 }));
    }

    private static void testServiceData() {
        byte[] data = new byte[] { 0x0D, 0x18, 0x00, 0x64 };
        assertTrue("识别 0x180D 服务数据", HeartRateParser.isHeartRateServiceData(data));
        assertEquals("服务数据 BPM=100", Integer.valueOf(100), HeartRateParser.parseServiceData(data));

        byte[] other = new byte[] { 0x0F, 0x18, 0x00, 0x64 };
        assertTrue("非 0x180D 不识别", !HeartRateParser.isHeartRateServiceData(other));
        assertNull("非 0x180D 不解析", HeartRateParser.parseServiceData(other));
        assertNull("过短的广播", HeartRateParser.parseServiceData(new byte[] { 0x0D, 0x18 }));
    }

    // ------------------------------------------------------------ OSC 解码器（仅测试用）

    private static final class Decoded {
        String address;
        final List<Object> values = new ArrayList<Object>();
    }

    private static Decoded decode(byte[] data) {
        Decoded decoded = new Decoded();
        int pos = 0;
        int end = pos;
        while (data[end] != 0) {
            end++;
        }
        decoded.address = new String(data, pos, end - pos, StandardCharsets.UTF_8);
        pos = (end + 4) & ~3;

        int tagEnd = pos;
        while (data[tagEnd] != 0) {
            tagEnd++;
        }
        String tags = new String(data, pos, tagEnd - pos, StandardCharsets.UTF_8);
        pos = (tagEnd + 4) & ~3;

        for (int i = 0; i < tags.length(); i++) {
            char tag = tags.charAt(i);
            if (tag == ',') {
                continue;
            }
            switch (tag) {
                case 'i':
                    decoded.values.add(Integer.valueOf((int) readInt(data, pos)));
                    pos += 4;
                    break;
                case 'f':
                    decoded.values.add(Float.valueOf(Float.intBitsToFloat((int) readInt(data, pos))));
                    pos += 4;
                    break;
                case 'T':
                    decoded.values.add(Boolean.TRUE);
                    break;
                case 'F':
                    decoded.values.add(Boolean.FALSE);
                    break;
                case 's': {
                    int start = pos;
                    int finish = pos;
                    while (data[finish] != 0) {
                        finish++;
                    }
                    decoded.values.add(new String(data, start, finish - start, StandardCharsets.UTF_8));
                    pos = (finish + 4) & ~3;
                    break;
                }
                default:
                    throw new IllegalStateException("未知类型标签: " + tag);
            }
        }
        assertTrue("OSC 包长度对齐 4 字节", pos % 4 == 0);
        return decoded;
    }

    private static int readInt(byte[] data, int pos) {
        return ((data[pos] & 0xFF) << 24)
                | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8)
                | (data[pos + 3] & 0xFF);
    }

    // ------------------------------------------------------------ 断言

    private static Object find(List<OscParams.Param> params, String address) {
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).address.equals(address)) {
                return params.get(i).value;
            }
        }
        return null;
    }

    private static void assertEquals(String name, Object expected, Object actual) {
        boolean same;
        if (expected instanceof Float && actual instanceof Float) {
            same = Math.abs(((Float) expected).floatValue() - ((Float) actual).floatValue()) < 0.0001f;
        } else {
            same = expected == null ? actual == null : expected.equals(actual);
        }
        if (same) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + "：期望 <" + expected + ">，实际 <" + actual + ">");
        }
    }

    private static void assertBytes(String name, byte[] expected, byte[] actual) {
        boolean same = expected.length == actual.length;
        if (same) {
            for (int i = 0; i < expected.length; i++) {
                if (expected[i] != actual[i]) {
                    same = false;
                    break;
                }
            }
        }
        if (same) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + "：字节不一致");
            System.out.println("        期望 " + toHex(expected));
            System.out.println("        实际 " + toHex(actual));
        }
    }

    private static void assertTrue(String name, boolean condition) {
        assertEquals(name, Boolean.TRUE, Boolean.valueOf(condition));
    }

    private static void assertNull(String name, Object value) {
        assertEquals(name, null, value);
    }

    private static void assertNotNull(String name, Object value) {
        if (value != null) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + "：不应为 null");
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", Byte.valueOf(data[i])));
        }
        return sb.toString();
    }
}
