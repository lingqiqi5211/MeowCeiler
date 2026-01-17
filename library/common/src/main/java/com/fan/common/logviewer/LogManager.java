package com.fan.common.logviewer;

import android.content.Context;
import android.util.Log;

import com.sevtinge.hyperceiler.libhook.utils.log.AndroidLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志管理器 - 单例模式
 * 功能：
 * 1. 管理应用日志和 Xposed 日志
 * 2. 日志持久化
 * 3. 日志导出
 * 4. 日志级别管理
 */
public class LogManager {
    private static LogManager sInstance;
    private final List<LogEntry> mLogEntries = new ArrayList<>();
    private final List<LogEntry> mXposedLogEntries = new ArrayList<>();
    private Context mContext;
    private boolean mInitialized = false;

    private static final String LOG_FILE = "app_logs.txt";
    private static final String LOG_CONFIG_FILE = "log_config";
    private static final String TAG = "LogManager";

    private LogManager() {}

    /**
     * 一键初始化（在 Application.onCreate() 中调用）
     */
    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (LogManager.class) {
                if (sInstance == null) {
                    sInstance = new LogManager();
                    sInstance.doInit(context.getApplicationContext());
                }
            }
        }
    }

    /**
     * 获取实例
     */
    public static LogManager getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException(
                "LogManager not initialized. Call LogManager.init(context) first.");
        }
        return sInstance;
    }

    private void doInit(Context context) {
        this.mContext = context;
        this.mInitialized = true;
        loadHistoryLogs();

        // 设置 AndroidLog 监听器，转发日志到 LogManager
        AndroidLog.setLogListener((level, tag, message) -> {
            try {
                LogEntry entry = new LogEntry(level, "App", "[" + tag + "] " + message, tag, true);
                addLog(entry);
            } catch (Throwable ignored) {
            }
        });

        addLog(new LogEntry("I", "LogManager", "LogManager initialized", "System", true));
    }

    // ===== 日志操作 =====
    public void addLog(LogEntry entry) {
        if (!mInitialized) return;
        mLogEntries.add(entry);
        saveLogAsync(entry);
    }

    public void addLogs(List<LogEntry> entries) {
        if (!mInitialized) return;
        mLogEntries.addAll(entries);
        saveLogsAsync(entries);
    }

    public void addXposedLog(LogEntry entry) {
        mXposedLogEntries.add(entry);
    }

    public void addXposedLogs(List<LogEntry> entries) {
        mXposedLogEntries.addAll(entries);
    }

    public void clearLogs() {
        mLogEntries.clear();
        deleteLogFile();
    }

    public void clearXposedLogs() {
        mXposedLogEntries.clear();
    }

    // ===== 获取数据 =====
    public List<LogEntry> getLogEntries() {
        return new ArrayList<>(mLogEntries);
    }

    public List<LogEntry> getXposedLogEntries() {
        return new ArrayList<>(mXposedLogEntries);
    }

    // ===== 文件操作 =====
    private void saveLogAsync(LogEntry entry) {
        new Thread(() -> {
            try {
                FileOutputStream fos = mContext.openFileOutput(LOG_FILE, Context.MODE_APPEND);
                String line = formatLogLine(entry);
                fos.write(line.getBytes());
                fos.close();
            } catch (IOException e) {
                Log.e(TAG, "Save log failed", e);
            }
        }).start();
    }

    private void saveLogsAsync(List<LogEntry> entries) {
        new Thread(() -> {
            try {
                FileOutputStream fos = mContext.openFileOutput(LOG_FILE, Context.MODE_APPEND);
                for (LogEntry entry : entries) {
                    String line = formatLogLine(entry);
                    fos.write(line.getBytes());
                }
                fos.close();
            } catch (IOException e) {
                Log.e(TAG, "Save logs failed", e);
            }
        }).start();
    }

    private void loadHistoryLogs() {
        try {
            FileInputStream fis = mContext.openFileInput(LOG_FILE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            String line;
            while ((line = reader.readLine()) != null) {
                LogEntry entry = parseLogLine(line);
                if (entry != null) {
                    mLogEntries.add(entry);
                }
            }
            reader.close();
        } catch (IOException ignored) {
            // 文件不存在是正常的
        }
    }

    private void deleteLogFile() {
        File file = new File(mContext.getFilesDir(), LOG_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    private String formatLogLine(LogEntry entry) {
        return String.format("%d|%s|%s|%s|%s|%b\n",
            entry.getTimestamp(),
            entry.getLevel(),
            entry.getModule(),
            entry.getMessage(),
            entry.getTag(),
            entry.isNewLine());
    }

    private LogEntry parseLogLine(String line) {
        String[] parts = line.split("\\|");
        if (parts.length == 6) {
            try {
                return new LogEntry(
                    Long.parseLong(parts[0]),
                    parts[1], parts[2], parts[3], parts[4],
                    Boolean.parseBoolean(parts[5])
                );
            } catch (Exception e) {
                Log.e(TAG, "Parse log line failed", e);
            }
        }
        return null;
    }

    /**
     * 导出日志
     */
    public boolean exportLogs(String fileName) {
        try {
            File exportFile = new File(mContext.getExternalFilesDir(null), fileName);
            FileOutputStream fos = new FileOutputStream(exportFile);

            for (LogEntry entry : mLogEntries) {
                String line = String.format("%s %s/%s: %s\n",
                    entry.getFormattedTime(),
                    entry.getModule(),
                    entry.getLevel(),
                    entry.getMessage());
                fos.write(line.getBytes());
            }
            fos.close();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Export failed", e);
            return false;
        }
    }
}
