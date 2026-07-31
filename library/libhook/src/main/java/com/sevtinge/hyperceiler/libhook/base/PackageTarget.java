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
package com.sevtinge.hyperceiler.libhook.base;

import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * 目标进程信息，是 libhook 下层唯一需要的加载参数。
 *
 * <p>只保存规则实际需要的三项：包名、{@link ApplicationInfo} 和目标 ClassLoader。
 * 普通进程在 {@code onPackageReady} 保存目标 ClassLoader，system_server 保存框架提供的
 * ClassLoader；{@code AppComponentFactory} 和首包状态不进入下层，首包判断只在
 * {@link XposedInitEntry} 的包回调里做一次。</p>
 *
 * <p>访问器同时覆盖 package 与 system_server 两条路径，命名与 libxposed 的
 * package 参数保持一致，因此规则侧的 {@code lpparam.packageName}、
 * {@code lpparam.classLoader} 写法无需改动。</p>
 *
 * @author HyperCeiler
 */
public final class PackageTarget {

    private final String packageName;
    @Nullable
    private final ApplicationInfo applicationInfo;
    private final ClassLoader classLoader;
    private final boolean systemServer;

    private PackageTarget(@NonNull String packageName, @Nullable ApplicationInfo applicationInfo,
                          @NonNull ClassLoader classLoader, boolean systemServer) {
        this.packageName = packageName;
        this.applicationInfo = applicationInfo;
        this.classLoader = classLoader;
        this.systemServer = systemServer;
    }

    /** 普通应用进程：在 {@code onPackageReady} 阶段使用框架提供的目标 ClassLoader。 */
    @NonNull
    public static PackageTarget ofPackageReady(@NonNull PackageReadyParam param) {
        return new PackageTarget(param.getPackageName(), param.getApplicationInfo(),
            param.getClassLoader(), false);
    }

    /** system_server 进程：没有常规意义上的 {@link ApplicationInfo}。 */
    @NonNull
    public static PackageTarget ofSystemServer(@NonNull SystemServerStartingParam param) {
        return new PackageTarget(BaseLoad.SYSTEM_SERVER, null, param.getClassLoader(), true);
    }

    /** 热重载后从 EzHookTool snapshot 与本项目 extras 重建。 */
    @NonNull
    public static PackageTarget restored(@NonNull String packageName,
                                         @Nullable ApplicationInfo applicationInfo,
                                         @NonNull ClassLoader classLoader, boolean systemServer) {
        return new PackageTarget(packageName, applicationInfo, classLoader, systemServer);
    }

    @NonNull
    public String getPackageName() {
        return packageName;
    }

    /**
     * 目标应用的 {@link ApplicationInfo}。
     *
     * <p>system_server 下为 {@code null}；DexKit 需要其中的 {@code dataDir}、{@code sourceDir}
     * 和 {@code splitSourceDirs}，因此热重载状态必须保留这一项。</p>
     */
    @Nullable
    public ApplicationInfo getApplicationInfo() {
        return applicationInfo;
    }

    /** 目标进程用于安装 Hook 的 ClassLoader，初次加载与热重载后都是同一语义。 */
    @NonNull
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public boolean isSystemServer() {
        return systemServer;
    }

    @NonNull
    @Override
    public String toString() {
        return "PackageTarget{" + packageName + ", systemServer=" + systemServer
            + ", classLoader=" + classLoader + '}';
    }
}
