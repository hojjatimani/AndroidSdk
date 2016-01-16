/******************************************************************************
 * Copyright 2015-2016 Befrest
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/


package rest.bef;

import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class FileLog {
    private static boolean DEBUG = true;
    private static final String LogsDir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/BefrestLogs";
    private OutputStreamWriter streamWriter = null;
    private SimpleDateFormat dateFormat;
    private FileLogThread logQueue = null;
    private File currentFile = null;
    private File networkFile = null;


    private static volatile FileLog Instance = null;

    private static FileLog getInstance() {
        FileLog localInstance = Instance;
        if (localInstance == null) {
            synchronized (FileLog.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new FileLog();
                }
            }
        }
        return localInstance;
    }

    private FileLog() {
        if (!DEBUG) {
            return;
        }
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        try {
            Log.d("FileLog", "path : " + LogsDir);
            File dir = new File(LogsDir);
            dir.mkdirs();
            currentFile = new File(dir, new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".txt");
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            logQueue = new FileLogThread("logQueue");
            currentFile.createNewFile();
            FileOutputStream stream = new FileOutputStream(currentFile);
            streamWriter = new OutputStreamWriter(stream);
            streamWriter.write("---start log " + dateFormat.format(System.currentTimeMillis()) + " (Pid:" + Process.myPid() + ")-----\n");
            streamWriter.flush();
//            l2("---start log " + dateFormat.format(System.currentTimeMillis()) + " (Pid:" + Process.myPid() + ")-----\n");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void e(final String tag, final String message, final Throwable exception) {
        if (!DEBUG) {
            return;
        }
        Log.e(tag, message, exception);
        final int Tid = Process.myTid();
        final long time = System.currentTimeMillis();
//        l2(getInstance().dateFormat.format(time + " (Tid:" + Tid + ")" + " E/" + tag + "﹕ " + message + "\n");
//        l2(exception.toString());
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(time) + " (Tid:" + Tid + ")" + " E/" + tag + "﹕ " + message + "\n");
                        getInstance().streamWriter.write(exception.toString());
                        getInstance().streamWriter.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public static void e(final String tag, final String message) {
        if (!DEBUG) {
            return;
        }
        Log.e(tag, message);
        final int Tid = Process.myTid();
        final long time = System.currentTimeMillis();
//        l2(getInstance().dateFormat.format(time) + " (Tid:" + Tid + ")" + " E/" + tag + "﹕ " + message + "\n");
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(time) + " (Tid:" + Tid + ")" + " E/" + tag + "﹕ " + message + "\n");
                        getInstance().streamWriter.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public static void e(final String tag, final Throwable e) {
        if (!DEBUG) {
            return;
        }
        e.printStackTrace();
        final int Tid = Process.myTid();
        final long time = System.currentTimeMillis();
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(time) + " (Tid:" + Tid + ")" + " E/" + tag + "﹕ " + e + "\n");
                        StackTraceElement[] stack = e.getStackTrace();
                        for (StackTraceElement el : stack) {
                            getInstance().streamWriter.write(getInstance().dateFormat.format(time) + " (Tid:" + Tid + ")" + " E/" + tag + "﹕ " + el + "\n");
                        }
                        getInstance().streamWriter.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        } else {
            e.printStackTrace();
        }
    }

    public static void d(final String tag, final String message) {
        if (!DEBUG) {
            return;
        }
        Log.d(tag, message);
        final int Tid = Process.myTid();
        final long time = System.currentTimeMillis();
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(time) + " (Tid:" + Tid + ")" + " D/" + tag + "﹕ " + message + "\n");
                        getInstance().streamWriter.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public static void w(final String tag, final String message) {
        if (!DEBUG) {
            return;
        }
        Log.w(tag, message);
        final int Tid = Process.myTid();
        final long time = System.currentTimeMillis();
        if (getInstance().streamWriter != null) {
            getInstance().logQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        getInstance().streamWriter.write(getInstance().dateFormat.format(time) + " (Tid:" + Tid + ")" + " W/" + tag + ": " + message + "\n");
                        getInstance().streamWriter.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public static void m(String TAG, String message, Object... objects) {
        if (!DEBUG) return;
        String s = "";
        for (Object o : objects) {
            s += o + ", ";
        }
        d(TAG, message + " " + "[" + s + "]");
    }

    public static void cleanupLogs() {
        File dir = new File(LogsDir);
        File[] files = dir.listFiles();
        for (File file : files) {
            if (getInstance().currentFile != null && file.getAbsolutePath().equals(getInstance().currentFile.getAbsolutePath())) {
                continue;
            }
            if (getInstance().networkFile != null && file.getAbsolutePath().equals(getInstance().networkFile.getAbsolutePath())) {
                continue;
            }
            file.delete();
        }
    }
}