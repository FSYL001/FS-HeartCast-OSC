package com.fsheartcast.osc;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 兜底的崩溃记录：把未捕获异常的堆栈写到应用私有目录，
 * 下次打开 App 时在「关于」里可以一键复制，方便排查闪退。
 */
public final class CrashHandler {

    private static final String TAG = "FSHeartCast";
    private static final String DIR = "crash";
    private static final String FILE = "last-crash.txt";

    private static boolean installed;

    private CrashHandler() {
    }

    public static void install(final Context context) {
        if (installed) {
            return;
        }
        installed = true;
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    write(appContext, throwable);
                } catch (Throwable ignored) {
                    // 记录失败也要让系统继续处理
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }

    private static void write(Context context, Throwable throwable) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        writer.println("time: " + time);
        writer.println("thread: " + Thread.currentThread().getName());
        writer.println("android: " + android.os.Build.VERSION.RELEASE + " (API "
                + android.os.Build.VERSION.SDK_INT + ")");
        writer.println("device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        writer.println("version: " + UpdateChecker.currentVersion(context));
        writer.println();
        throwable.printStackTrace(writer);
        writer.flush();

        String text = buffer.toString();
        Log.e(TAG, text);

        File dir = new File(context.getExternalFilesDir(null), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File file = new File(dir, FILE);
        try {
            FileWriter fileWriter = new FileWriter(file, false);
            fileWriter.write(text);
            fileWriter.close();
        } catch (Exception ignored) {
            // 写不进去就算了
        }
    }

    /** 读取上次崩溃记录，没有则返回 null。 */
    public static String readLast(Context context) {
        try {
            File file = new File(new File(context.getExternalFilesDir(null), DIR), FILE);
            if (!file.exists()) {
                return null;
            }
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            reader.close();
            return builder.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context context) {
        try {
            File file = new File(new File(context.getExternalFilesDir(null), DIR), FILE);
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception ignored) {
            // ignore
        }
    }
}
