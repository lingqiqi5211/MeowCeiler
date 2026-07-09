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
package com.sevtinge.hyperceiler.utils;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sevtinge.hyperceiler.common.log.AndroidLog;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.HotReloadResult;
import io.github.libxposed.service.XposedService;

import java.util.Collections;
import java.util.List;

/**
 * 热重载管理器（libxposed API 102 能力封装）。
 *
 * <p>提供：</p>
 * <ul>
 *     <li>{@link #isHotReloadAvailable()}：检测当前 framework 是否支持热重载（service API >= 102）；</li>
 *     <li>{@link #getRunningTargets()}：列出本模块当前已注入的目标进程；</li>
 *     <li>{@link #hotReloadByPackage(String, HotReloadCallback)} / {@link #hotReloadAll(HotReloadCallback)}：
 *         按包名 / 全部目标发起一次显式热重载请求；</li>
 *     <li>所有结果回调在主线程派发。</li>
 * </ul>
 */
public final class HotReloadManager {

    private static final String TAG = "HotReloadManager";

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private HotReloadManager() {
    }

    /**
     * 当前 framework 是否支持热重载（即 XposedService API >= 102 且 service 已绑定）。
     */
    public static boolean isHotReloadAvailable() {
        XposedService service = ScopeManager.getService();
        if (service == null) return false;
        try {
            return service.getApiVersion() >= XposedService.API_102;
        } catch (Throwable t) {
            AndroidLog.w(TAG, "isHotReloadAvailable failed", t);
            return false;
        }
    }

    /**
     * 获取当前模块已注入的运行中目标进程列表。
     *
     * @return 不可修改的列表；当 framework 不支持或调用失败时返回空列表
     */
    @NonNull
    public static List<HookedTarget> getRunningTargets() {
        XposedService service = ScopeManager.getService();
        if (service == null) return Collections.emptyList();
        try {
            if (service.getApiVersion() < XposedService.API_102) {
                return Collections.emptyList();
            }
            return service.getRunningTargets();
        } catch (UnsupportedOperationException uoe) {
            AndroidLog.w(TAG, "getRunningTargets unsupported: " + uoe.getMessage());
            return Collections.emptyList();
        } catch (Throwable t) {
            AndroidLog.w(TAG, "getRunningTargets failed", t);
            return Collections.emptyList();
        }
    }

    /**
     * 按包名筛选并触发热重载。若同包名下有多个进程都注入了本模块，会全部触发。
     *
     * @param packageName 目标包名（不可为空）
     * @param callback    回调（main thread）
     * @return 是否成功提交至少一个热重载请求
     */
    public static boolean hotReloadByPackage(@NonNull String packageName,
                                             @NonNull HotReloadCallback callback) {
        List<HookedTarget> all = getRunningTargets();
        if (all.isEmpty()) {
            AndroidLog.w(TAG, "hotReloadByPackage(" + packageName + "): no running targets");
            postNotAvailable(callback);
            return false;
        }

        // 调试用：把当前所有 target 的 processName 打印出来，便于在 LSPosed/不同框架下
        // 排查 system_server 别名问题
        StringBuilder sb = new StringBuilder("hotReloadByPackage(").append(packageName)
            .append("): available targets =");
        for (HookedTarget t : all) {
            sb.append(" [").append(t.getProcessName())
              .append(" pid=").append(t.getPid())
              .append(" state=").append(t.getState()).append("]");
        }
        AndroidLog.d(TAG, sb.toString());

        int submitted = 0;
        for (HookedTarget target : all) {
            if (matchesPackage(target, packageName)) {
                hotReloadTarget(target, null, callback);
                submitted++;
            }
        }
        if (submitted == 0) {
            AndroidLog.w(TAG, "hotReloadByPackage(" + packageName + "): no matching target");
            postNoMatch(callback, packageName);
            return false;
        }
        return true;
    }

    /** 对所有运行中的目标进程触发一次热重载。 */
    public static boolean hotReloadAll(@NonNull HotReloadCallback callback) {
        List<HookedTarget> all = getRunningTargets();
        if (all.isEmpty()) {
            postNotAvailable(callback);
            return false;
        }
        for (HookedTarget target : all) {
            hotReloadTarget(target, null, callback);
        }
        return true;
    }

    /**
     * 直接对指定目标触发热重载。
     *
     * @param target   目标（来自 {@link #getRunningTargets()}）
     * @param extras   附加数据（可空）
     * @param callback 回调（main thread）
     */
    public static void hotReloadTarget(@NonNull HookedTarget target,
                                       @Nullable Bundle extras,
                                       @NonNull HotReloadCallback callback) {
        XposedService service = ScopeManager.getService();
        if (service == null) {
            postResult(callback, target, ResultCode.SERVICE_UNAVAILABLE, "Xposed service not bound");
            return;
        }
        try {
            service.hotReloadModule(target, extras, (t, result) ->
                postResult(callback, t, ResultCode.fromStatus(result.status()), result.message()));
        } catch (UnsupportedOperationException uoe) {
            postResult(callback, target, ResultCode.UNSUPPORTED, uoe.getMessage());
        } catch (SecurityException se) {
            postResult(callback, target, ResultCode.INVALID_TARGET, se.getMessage());
        } catch (Throwable t) {
            postResult(callback, target, ResultCode.SERVICE_ERROR, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean matchesPackage(@NonNull HookedTarget target, @NonNull String packageName) {
        String processName = target.getProcessName();
        if (processName == null) return false;
        if (processName.equals(packageName)) return true;

        // system_server 别名：LSPosed / 不同 framework 实现历史上对 system_server 的进程名
        // 写法不一致，常见的有 "system_server"（标准 Android 进程名）和 "system"
        // （HyperCeiler / 部分 framework 内部约定）。两个互为别名。
        // 注意 "android" 是 framework 自身的 manifest 包名，<b>不是</b> 进程名，不在此别名集合内。
        if (isSystemServerName(packageName) && isSystemServerName(processName)) {
            return true;
        }

        // 多进程 (例如 com.miui.home:wallpaper) 也算
        int colon = processName.indexOf(':');
        if (colon > 0) {
            return processName.substring(0, colon).equals(packageName);
        }
        return false;
    }

    private static boolean isSystemServerName(@NonNull String name) {
        return "system_server".equals(name) || "system".equals(name);
    }

    private static void postNotAvailable(@NonNull HotReloadCallback callback) {
        sMain.post(() -> callback.onResult(null, ResultCode.SERVICE_UNAVAILABLE,
            "Hot reload service unavailable or no running targets"));
    }

    private static void postNoMatch(@NonNull HotReloadCallback callback, @NonNull String pkg) {
        sMain.post(() -> callback.onResult(null, ResultCode.NO_MATCHING_TARGET,
            "No running hooked target matches package: " + pkg));
    }

    private static void postResult(@NonNull HotReloadCallback callback,
                                   @Nullable HookedTarget target,
                                   @NonNull ResultCode code,
                                   @Nullable String message) {
        String detail = normalizeMessage(target, code, message);
        sMain.post(() -> callback.onResult(target, code, detail));
    }

    @NonNull
    private static String normalizeMessage(@Nullable HookedTarget target,
                                           @NonNull ResultCode code,
                                           @Nullable String message) {
        if (message != null && !message.trim().isEmpty()) {
            return message;
        }

        String targetState = target == null ? "" : " Target state: " + target.getState().name() + ".";
        return switch (code) {
            case SUCCEEDED -> "Hot reload completed.";
            case FAILED -> "Framework reported FAILED without details. "
                + "The module rejected this target before switching generation, usually because reload state was incomplete."
                + targetState;
            case UNSUPPORTED -> "Hot reload is not supported by the current framework.";
            case IN_PROGRESS -> "The target process is already reloading." + targetState;
            case PROCESS_DIED -> "The target process died during hot reload." + targetState;
            case INVALID_TARGET -> "The target process is invalid or no longer running." + targetState;
            case SERVICE_UNAVAILABLE -> "Xposed service is unavailable.";
            case SERVICE_ERROR -> "Xposed service call failed without details.";
            case NO_MATCHING_TARGET -> "No matching hooked target is running.";
        };
    }

    public enum ResultCode {
        SUCCEEDED,
        FAILED,
        UNSUPPORTED,
        IN_PROGRESS,
        PROCESS_DIED,
        INVALID_TARGET,
        SERVICE_UNAVAILABLE,
        SERVICE_ERROR,
        NO_MATCHING_TARGET;

        static ResultCode fromStatus(HotReloadResult.Status status) {
            return switch (status) {
                case SUCCEEDED -> SUCCEEDED;
                case FAILED -> FAILED;
                case UNSUPPORTED -> UNSUPPORTED;
                case IN_PROGRESS -> IN_PROGRESS;
                case PROCESS_DIED -> PROCESS_DIED;
            };
        }
    }

    @FunctionalInterface
    public interface HotReloadCallback {
        /**
         * @param target  目标（提交失败时为 null）
         * @param code    结果码
         * @param message 详细信息（可空）
         */
        @MainThread
        void onResult(@Nullable HookedTarget target, @NonNull ResultCode code, @Nullable String message);
    }
}
