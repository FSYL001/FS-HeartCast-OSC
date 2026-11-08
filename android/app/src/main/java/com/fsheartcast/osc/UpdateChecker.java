package com.fsheartcast.osc;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查 GitHub Releases 的最新版本。
 * 只读取公开 API，不需要 token；请求在后台线程，结果回主线程。
 */
public final class UpdateChecker {

    public static final String REPO = "FSYL001/FS-HeartCast-OSC";
    private static final String LATEST_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String USER_AGENT = "FS-HeartCast-OSC-Android";

    private UpdateChecker() {
    }

    public static final class Release {
        public final String tag;
        public final String name;
        public final String notes;
        public final String pageUrl;
        public final String apkUrl;

        Release(String tag, String name, String notes, String pageUrl, String apkUrl) {
            this.tag = tag;
            this.name = name;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
        }
    }

    public interface Callback {
        /**
         * @param release 最新发布（失败时为 null）
         * @param newer   是否比当前版本新
         * @param error   失败原因（成功时为 null）
         */
        void onResult(Release release, boolean newer, String error);
    }

    public static void check(final Context context, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Release release = null;
                String error = null;
                boolean newer = false;
                try {
                    JSONObject json = fetchJson(LATEST_URL);
                    String tag = json.optString("tag_name", "");
                    String name = json.optString("name", tag);
                    String notes = json.optString("body", "");
                    String pageUrl = json.optString("html_url", "https://github.com/" + REPO + "/releases");

                    String apkUrl = null;
                    JSONArray assets = json.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.optJSONObject(i);
                            if (asset == null) {
                                continue;
                            }
                            String assetName = asset.optString("name", "");
                            if (assetName.toLowerCase().endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", null);
                                if (apkUrl != null && apkUrl.length() > 0) {
                                    break;
                                }
                            }
                        }
                    }

                    release = new Release(tag, name, notes, pageUrl, apkUrl);
                    newer = compareVersions(tag, currentVersion(appContext)) > 0;
                } catch (Throwable e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                final Release finalRelease = release;
                final boolean finalNewer = newer;
                final String finalError = error;
                try {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                callback.onResult(finalRelease, finalNewer, finalError);
                            } catch (Throwable t) {
                                android.util.Log.e("FSHeartCast", "update callback failed", t);
                            }
                        }
                    });
                } catch (Throwable ignored) {
                    // 主线程不可用时忽略
                }
            }
        }, "update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static JSONObject fetchJson(String urlText) throws Exception {
        String currentUrl = urlText;
        for (int redirectCount = 0; redirectCount < 3; redirectCount++) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(currentUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String newUrl = connection.getHeaderField("Location");
                    if (newUrl != null && newUrl.length() > 0) {
                        currentUrl = newUrl;
                        continue;
                    }
                }
                if (code != 200) {
                    throw new IllegalStateException("HTTP " + code);
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
                reader.close();
                return new JSONObject(builder.toString());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw new IllegalStateException("Too many redirects");
    }

    public static String currentVersion(Context context) {
        try {
            String versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return versionName == null ? "0" : versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** 语义化版本比较，忽略前缀 v 和非数字后缀。 */
    public static int compareVersions(String left, String right) {
        int[] a = parseParts(left);
        int[] b = parseParts(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y ? 1 : -1;
            }
        }
        return 0;
    }

    private static int[] parseParts(String version) {
        if (version == null) {
            return new int[0];
        }
        String value = version.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        String[] pieces = value.split("[^0-9]+");
        int count = 0;
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i].length() > 0) {
                count++;
            }
        }
        int[] result = new int[count];
        int index = 0;
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i].length() == 0) {
                continue;
            }
            try {
                result[index++] = Integer.parseInt(pieces[i]);
            } catch (NumberFormatException e) {
                result[index++] = 0;
            }
        }
        return result;
    }
}
