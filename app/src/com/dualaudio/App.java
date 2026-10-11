package com.dualaudio;

import android.app.Application;
import android.os.Process;

import java.io.File;
import java.io.FileWriter;

/**
 * 全局崩溃记录：任何未捕获异常都会写到
 *   /data/data/com.dualaudio/files/crash.txt
 * 用 MT管理器 可以直接打开查看。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    File f = new File(getFilesDir(), "crash.txt");
                    FileWriter w = new FileWriter(f, false);
                    w.write("thread=" + t.getName() + "\n");
                    w.write(e.toString() + "\n");
                    for (StackTraceElement el : e.getStackTrace()) {
                        w.write("    at " + el + "\n");
                    }
                    Throwable c = e.getCause();
                    while (c != null) {
                        w.write("Caused by: " + c + "\n");
                        for (StackTraceElement el : c.getStackTrace()) {
                            w.write("    at " + el + "\n");
                        }
                        c = c.getCause();
                    }
                    w.close();
                } catch (Throwable ignore) {
                }
                if (def != null) def.uncaughtException(t, e);
                Process.killProcess(Process.myPid());
            }
        });
    }
}
