/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.utils.log

/**
 * 日志管理器
 *
 */
object LogManager {

    @JvmField
    var IS_LOGGER_ALIVE: Boolean = false

    var logLevel: Int = 3
        private set

    @JvmStatic
    fun init(appPrivateDir: String) {
        // 初始化配置管理器
        LogConfigManager.init(appPrivateDir)

        // 检查日志服务
        IS_LOGGER_ALIVE = LoggerHealthChecker.isLoggerAlive()

        // 读取日志级别
        logLevel = LogConfigManager.readLogLevel()

        // 同步日志级别到其他日志类
        XposedLog.logLevel = logLevel
        AndroidLog.logLevel = logLevel
    }

    @JvmStatic
    fun setLogLevel(level: Int) {
        val effectiveLogLevel = LogLevelManager.getEffectiveLogLevel(level)
        LogConfigManager.writeLogLevel(effectiveLogLevel)
        logLevel = effectiveLogLevel

        // 同步到其他日志类
        XposedLog.logLevel = logLevel
        AndroidLog.logLevel = logLevel
    }

    @JvmStatic
    fun setLogLevel(level: Int, basePath: String?) {
        val effectiveLogLevel = LogLevelManager.getEffectiveLogLevel(level)
        LogConfigManager.writeLogLevel(basePath, effectiveLogLevel)
        logLevel = effectiveLogLevel

        // 同步到其他日志类
        XposedLog.logLevel = logLevel
        AndroidLog.logLevel = logLevel
    }

    @JvmStatic
    fun readLogLevelFromFile(): Int {
        return LogConfigManager.readLogLevel()
    }

    @JvmStatic
    fun readLogLevelFromFile(basePath: String?): Int {
        return LogConfigManager.readLogLevel(basePath)
    }

    @JvmStatic
    fun logLevelDesc(): String {
        return LogLevelManager.logLevelDesc(logLevel)
    }

    @JvmStatic
    fun fixLSPosedLogService(): String {
        return LoggerHealthChecker.fixLSPosedLogService()
    }
}
