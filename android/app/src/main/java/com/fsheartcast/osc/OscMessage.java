package com.fsheartcast.osc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * OSC 1.0 消息编码（纯 Java，不依赖 Android，便于单元测试）。
 * 规则：地址与类型标签都是 null 结尾、4 字节对齐；int/float 大端；bool 只用类型标签 T/F。
 */
public final class OscMessage {

    private OscMessage() {
    }

    public static byte[] build(String oscAddress, Object[] values) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream(128);
        writeString(stream, oscAddress);

        StringBuilder tags = new StringBuilder(",");
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value instanceof Boolean) {
                tags.append(((Boolean) value).booleanValue() ? 'T' : 'F');
            } else if (value instanceof Float || value instanceof Double) {
                tags.append('f');
            } else if (value instanceof String) {
                tags.append('s');
            } else {
                tags.append('i');
            }
        }
        writeString(stream, tags.toString());

        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value instanceof Boolean) {
                continue;
            }
            if (value instanceof Float) {
                writeFloat(stream, ((Float) value).floatValue());
            } else if (value instanceof Double) {
                writeFloat(stream, (float) ((Double) value).doubleValue());
            } else if (value instanceof String) {
                writeString(stream, (String) value);
            } else if (value instanceof Integer) {
                writeInt(stream, ((Integer) value).intValue());
            } else {
                writeInt(stream, 0);
            }
        }
        return stream.toByteArray();
    }

    static void writeString(ByteArrayOutputStream stream, String value) {
        if (value == null) {
            value = "";
        }
        byte[] bytes;
        try {
            bytes = value.getBytes("UTF-8");
        } catch (IOException e) {
            bytes = value.getBytes();
        }
        stream.write(bytes, 0, bytes.length);
        stream.write(0);
        while (stream.size() % 4 != 0) {
            stream.write(0);
        }
    }

    static void writeInt(ByteArrayOutputStream stream, int value) {
        stream.write((value >>> 24) & 0xFF);
        stream.write((value >>> 16) & 0xFF);
        stream.write((value >>> 8) & 0xFF);
        stream.write(value & 0xFF);
    }

    static void writeFloat(ByteArrayOutputStream stream, float value) {
        writeInt(stream, Float.floatToIntBits(value));
    }
}
