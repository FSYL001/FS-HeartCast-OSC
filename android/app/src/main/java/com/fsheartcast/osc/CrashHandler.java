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

    /** 主动记录一次已捕获但严重的异常。 */
    public static void record(Context context, Throwable throwable) {
        try {
            write(context.getApplicationContext(), throwable);
        } catch (Throwable ignored) {
        }
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

        // 1. 写应用内部私有目录（保证 100% 成功，不受外部存储状态或权限限制）
        try {
            File internalDir = new File(context.getFilesDir(), DIR);
            writeToDir(internalDir, text);
        } catch (Throwable ignored) {
        }

        // 2. 写外部私有目录（方便通过电脑或文件管理器直接查看）
        try {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) {
                File externalDir = new File(ext, DIR);
                writeToDir(externalDir, text);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeToDir(File dir, String text) {
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File file = new File(dir, FILE);
        try {
            FileWriter fileWriter = new FileWriter(file, false);
            fileWriter.write(text);
            fileWriter.close();
        } catch (Exception ignored) {
        }
    }

    /** 读取上次崩溃记录，没有则返回 null。 */
    public static String readLast(Context context) {
        // 先读内部私有目录
        try {
            File internalFile = new File(new File(context.getFilesDir(), DIR), FILE);
            String text = readFile(internalFile);
            if (text != null && text.length() > 0) {
                return text;
            }
        } catch (Throwable ignored) {
        }

        // 再读外部私有目录
        try {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) {
                File externalFile = new File(new File(ext, DIR), FILE);
                String text = readFile(externalFile);
                if (text != null && text.length() > 0) {
                    return text;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readFile(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
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
