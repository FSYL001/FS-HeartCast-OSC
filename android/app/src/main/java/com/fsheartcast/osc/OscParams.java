package com.fsheartcast.osc;

import java.util.ArrayList;
import java.util.List;

/**
 * 姣忔蹇冭烦瑕佸彂閫佺殑 OSC 鍙傛暟娓呭崟锛堢函 Java锛屽彲鍦?JVM 涓婄洿鎺ュ崟鍏冩祴璇曪級銆? * 鍙傛暟鍚嶄笌鍏紡鍜屾闈㈢増 HOSC/Services/OscService.cs 淇濇寔涓€鑷达紝
 * 淇濊瘉鍚屼竴涓?VRChat Avatar 鍦?PC 鐗堝拰鎵嬫満鐗堜箣闂村彲浠ョ洿鎺ュ垏鎹€? */
public final class OscParams {

    public static final String VRCOSC_VALUE = "/avatar/parameters/VRCOSC/Heartrate/Value";
    public static final String VRCOSC_BEAT = "/avatar/parameters/VRCOSC/Heartrate/Beat";
    public static final String VRCOSC_NORMALISED = "/avatar/parameters/VRCOSC/Heartrate/Normalised";
    public static final String VRCOSC_AVERAGE = "/avatar/parameters/VRCOSC/Heartrate/Average";
    public static final String VRCOSC_CONNECTED = "/avatar/parameters/VRCOSC/Heartrate/Connected";
    public static final String VRCOSC_ENABLED = "/avatar/parameters/VRCOSC/Heartrate/Enabled";

    public static final String HEART_BEAT_TOGGLE = "/avatar/parameters/HeartBeatToggle";
    public static final String HEART_RATE_INT = "/avatar/parameters/HeartRateInt";
    public static final String HEART_RATE3 = "/avatar/parameters/HeartRate3";
    public static final String HEARTRATE3 = "/avatar/parameters/Heartrate3";
    public static final String HEART_RATE_FLOAT01 = "/avatar/parameters/HeartRateFloat01";
    public static final String HR_PERCENT = "/avatar/parameters/HRPercent";
    public static final String HEART_RATE = "/avatar/parameters/HeartRate";
    public static final String IS_HR_CONNECTED = "/avatar/parameters/isHRConnected";
    public static final String IS_HR_ACTIVE = "/avatar/parameters/isHRActive";

    /** 涓庢闈㈢増涓€鑷寸殑榛樿鍙傛暟鍦板潃銆?*/
    public static final String DEFAULT_HR_ADDRESS = "/avatar/parameters/HR";
    public static final String DEFAULT_BEAT_ADDRESS = "/avatar/parameters/isHRBeat";

    private OscParams() {
    }

    public static final class Param {
        public final String address;
        public final Object value;

        Param(String address, Object value) {
            this.address = address;
            this.value = value;
        }
    }

    /**
     * 鍗曟蹇冪巼鎺ㄩ€佺殑鍙傛暟娓呭崟锛堜笉鍚?120ms 鍚庢妸蹇冭烦鍦板潃澶嶄綅涓?0 鐨勯偅涓€鏉★級銆?     *
     * @param toggle 蹇冭烦缈昏浆鍊硷紝鐢ㄤ簬 HeartBeatToggle / VRCOSC Beat
     */
    public static List<Param> heartRate(int bpm, int averageBpm, boolean toggle,
                                        String hrAddress, String beatAddress) {
        List<Param> list = new ArrayList<Param>(13);
        int avg = averageBpm > 0 ? averageBpm : bpm;

        list.add(new Param(normalize(hrAddress, DEFAULT_HR_ADDRESS), Integer.valueOf(bpm)));
        list.add(new Param(normalize(beatAddress, DEFAULT_BEAT_ADDRESS), Integer.valueOf(1)));
        list.add(new Param(HEART_BEAT_TOGGLE, Boolean.valueOf(toggle)));

        list.add(new Param(VRCOSC_VALUE, Integer.valueOf(bpm)));
        list.add(new Param(VRCOSC_BEAT, Boolean.valueOf(toggle)));
        list.add(new Param(VRCOSC_NORMALISED, Float.valueOf(clamp(bpm / 240f, 0f, 1f))));
        list.add(new Param(VRCOSC_AVERAGE, Integer.valueOf(avg)));

        list.add(new Param(HEART_RATE_INT, Integer.valueOf(bpm)));
        list.add(new Param(HEART_RATE3, Integer.valueOf(bpm)));
        list.add(new Param(HEARTRATE3, Integer.valueOf(bpm)));
        list.add(new Param(HEART_RATE_FLOAT01, Float.valueOf(clamp(bpm / 240f, 0f, 1f))));
        list.add(new Param(HR_PERCENT, Float.valueOf(clamp(bpm / 255f, 0f, 1f))));
        list.add(new Param(HEART_RATE, Float.valueOf(clamp(bpm / 127.5f - 1f, -1f, 1f))));
        return list;
    }

    /** 杩炴帴鐘舵€佸洓鍙傛暟銆?*/
    public static List<Param> connected(boolean connected) {
        List<Param> list = new ArrayList<Param>(4);
        list.add(new Param(VRCOSC_CONNECTED, Boolean.valueOf(connected)));
        list.add(new Param(VRCOSC_ENABLED, Boolean.valueOf(connected)));
        list.add(new Param(IS_HR_CONNECTED, Boolean.valueOf(connected)));
        list.add(new Param(IS_HR_ACTIVE, Boolean.valueOf(connected)));
        return list;
    }

    public static String normalize(String address, String fallback) {
        if (address == null) {
            return fallback;
        }
        String a = address.trim();
        if (a.length() == 0) {
            return fallback;
        }
        if (a.charAt(0) != '/') {
            a = "/" + a;
        }
        return a;
    }

    public static float clamp(float v, float min, float max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }
}
