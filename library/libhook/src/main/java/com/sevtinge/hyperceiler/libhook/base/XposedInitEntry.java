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

import android.app.AppComponentFactory;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sevtinge.hyperceiler.common.log.LogStatusManager;
import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.app.CorePatch.CorePatch;
import com.sevtinge.hyperceiler.libhook.rules.systemframework.others.FlagSecure;
import com.sevtinge.hyperceiler.libhook.safecrash.CrashMonitor;
import com.sevtinge.hyperceiler.libhook.utils.api.ContextUtils;
import com.sevtinge.hyperceiler.libhook.utils.api.ThreadPoolManager;

import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed;

/**
 * Xposed 模块入口（libxposed API 102）。
 *
 * @author HyperCeiler
 */
public class XposedInitEntry extends XposedModule {

    private static final String TAG = "HyperCeiler";
    private static final String PREF_ALLOW_HOOK = "allow_hook";
    private static final String PREF_FRAMEWORK_ALLOW_HOOK = "framework_api_allow_hook";
    private static final String PREF_FRAMEWORK_REASON = "framework_check_reason";
    private static final String PREF_FRAMEWORK_NAME = "framework_check_name";
    private static final String PREF_FRAMEWORK_VERSION = "framework_check_version";
    private static final String PREF_FRAMEWORK_VERSION_CODE = "framework_check_version_code";

    private static final String HOT_RELOAD_STATE_MAGIC = "HyperCeiler.HotReloadState";
    private static final int HOT_RELOAD_STATE_VERSION = 1;
    private static final int IDX_MAGIC = 0;
    private static final int IDX_VERSION = 1;
    private static final int IDX_KIND = 2;
    private static final int IDX_PKG = 3;
    private static final int IDX_PROC = 4;
    private static final int IDX_CLASSLOADER = 5;
    private static final int IDX_APP_INFO = 6;
    private static final int IDX_IS_FIRST_PKG = 7;
    private static final int IDX_APP_CONTEXT = 8;
    private static final int STATE_LEN = 9;

    protected String processName;
    private final Object prefsInitLock = new Object();
    private volatile boolean prefsInited = false;

    @Nullable
    private volatile Object mLastLpparam;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        initModule(param);
    }

    private void initModule(@NonNull ModuleLoadedParam param) {
        processName = param.getProcessName();
        try {
            initPrefs();
        } catch (Throwable t) {
            XposedLog.w(TAG, processName, "Failed to initialize prefs during module bootstrap, will retry later.", t);
        }
        EzXposed.initOnModuleLoaded(this, param);
        BaseLoad.init(this);
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam lpparam) {
        if (prepareHookLoad("system")) {
            return;
        }
        attachHookLogLevelObserver(true);

        EzXposed.initOnSystemServerStarting(lpparam);
        loadSystemEntryHooks(lpparam);

        mLastLpparam = lpparam;

        invokeInit(lpparam);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam lpparam) {
        super.onPackageReady(lpparam);
        if (!lpparam.isFirstPackage()) return;
        String packageName = lpparam.getPackageName();
        if (prepareHookLoad(packageName)) {
            return;
        }

        EzXposed.initOnPackageReady(lpparam);
        attachHookLogLevelObserver(false);

        mLastLpparam = lpparam;

        invokeInit(lpparam);
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        Object[] state;
        try {
            state = buildHotReloadState();
        } catch (Throwable t) {
            XposedLog.e(TAG, processName, "Hot reload rejected: failed to capture process state", t);
            return false;
        }
        if (state == null) {
            XposedLog.w(TAG, processName, "Hot reload rejected: process state is incomplete.");
            return false;
        }
        try {
            param.setSavedInstanceState(state);
        } catch (Throwable t) {
            XposedLog.e(TAG, processName, "Hot reload rejected: failed to save state", t);
            return false;
        }

        prepareForHotReload();
        XposedLog.i(TAG, processName, "Hot reload accepted.");
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        initModule(param);
        HotReloadState state = restoreHotReloadState(param.getSavedInstanceState());
        if (state == null) {
            XposedLog.e(TAG, processName, "Hot reload ignored: saved state is missing or incompatible.");
            return;
        }

        try {
            for (Object handle : param.getOldHookHandles()) {
                if (!(handle instanceof XposedInterface.HookHandle h)) {
                    continue;
                }
                try {
                    h.unhook();
                } catch (Throwable t) {
                    XposedLog.w(TAG, processName, "Failed to unhook old handle", t);
                }
            }
        } catch (Throwable t) {
            XposedLog.w(TAG, processName, "Failed to iterate old hook handles", t);
        }

        try {
            if (!TextUtils.isEmpty(state.processName())) {
                processName = state.processName();
            }
            if (state.appContext() != null) {
                EzXposed.initAppContext(state.appContext(), false, true);
            }

            if ("system".equals(state.kind())) {
                SystemServerStartingParam adapter = new RestoredSystemServerParam(state.classLoader());
                mLastLpparam = adapter;
                if (prepareHookLoad("system")) {
                    return;
                }
                attachHookLogLevelObserver(true);
                EzXposed.initOnSystemServerStarting(adapter);
                loadSystemEntryHooks(adapter);
                XposedLog.i(TAG, "system", "Hot reload: reinstalling hooks for system_server");
                invokeInit(adapter);
            } else if ("pkg".equals(state.kind())) {
                PackageReadyParam adapter = new RestoredPackageReadyParam(
                    state.packageName(),
                    state.classLoader(),
                    state.applicationInfo(),
                    state.firstPackage()
                );
                mLastLpparam = adapter;
                if (prepareHookLoad(state.packageName())) {
                    return;
                }
                attachHookLogLevelObserver(false);
                EzXposed.initOnPackageReady(adapter);
                XposedLog.i(TAG, state.packageName(), "Hot reload: reinstalling hooks for package");
                invokeInit(adapter);
            } else {
                XposedLog.e(TAG, processName, "Hot reload: unknown state kind=" + state.kind());
            }
        } catch (Throwable t) {
            XposedLog.e(TAG, processName, "Hot reload re-init failed", t);
        }
    }

    @Nullable
    private Object[] buildHotReloadState() {
        Object lpparam = mLastLpparam;
        Context appContext = null;
        try {
            appContext = EzXposed.getAppContextOrNull();
        } catch (Throwable t) {
            XposedLog.d(TAG, processName, "Application context is unavailable during hot reload snapshot.");
        }
        if (lpparam instanceof SystemServerStartingParam systemParam) {
            ClassLoader cl = systemParam.getClassLoader();
            if (cl == null) return null;
            return new Object[]{
                HOT_RELOAD_STATE_MAGIC,
                HOT_RELOAD_STATE_VERSION,
                "system",
                BaseLoad.SYSTEM_SERVER,
                processName,
                cl,
                null,
                false,
                appContext
            };
        }
        if (lpparam instanceof PackageReadyParam packageParam) {
            ClassLoader cl = packageParam.getClassLoader();
            String pkg = packageParam.getPackageName();
            ApplicationInfo appInfo = packageParam.getApplicationInfo();
            if (TextUtils.isEmpty(pkg) || cl == null || appInfo == null) return null;
            return new Object[]{
                HOT_RELOAD_STATE_MAGIC,
                HOT_RELOAD_STATE_VERSION,
                "pkg",
                pkg,
                processName,
                cl,
                appInfo,
                packageParam.isFirstPackage(),
                appContext
            };
        }
        return null;
    }

    private void prepareForHotReload() {
        try {
            BaseLoad.prepareHotReload();
        } catch (Throwable t) {
            XposedLog.w(TAG, processName, "Failed to reset hook runtime before hot reload", t);
        }
        try {
            LogStatusManager.detachHookLogLevelObserver();
        } catch (Throwable t) {
            XposedLog.w(TAG, processName, "Failed to detach log observer before hot reload", t);
        }
        try {
            ThreadPoolManager.shutdownAndAwait(500, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            XposedLog.w(TAG, processName, "Failed to stop background tasks before hot reload", t);
        }
    }

    @Nullable
    private HotReloadState restoreHotReloadState(@Nullable Object savedRaw) {
        if (!(savedRaw instanceof Object[] state) || state.length < STATE_LEN) {
            return null;
        }
        if (!HOT_RELOAD_STATE_MAGIC.equals(state[IDX_MAGIC])
            || !Integer.valueOf(HOT_RELOAD_STATE_VERSION).equals(state[IDX_VERSION])) {
            return null;
        }
        if (!(state[IDX_KIND] instanceof String kind)
            || !(state[IDX_PKG] instanceof String pkg)
            || !(state[IDX_CLASSLOADER] instanceof ClassLoader classLoader)) {
            return null;
        }
        String savedProcessName = state[IDX_PROC] instanceof String proc ? proc : null;
        ApplicationInfo appInfo = state[IDX_APP_INFO] instanceof ApplicationInfo info ? info : null;
        Context appContext = state[IDX_APP_CONTEXT] instanceof Context context ? context : null;
        boolean firstPackage = Boolean.TRUE.equals(state[IDX_IS_FIRST_PKG]);

        if ("system".equals(kind)) {
            return new HotReloadState(kind, BaseLoad.SYSTEM_SERVER, savedProcessName,
                classLoader, null, false, appContext);
        }
        if (!"pkg".equals(kind) || TextUtils.isEmpty(pkg) || appInfo == null) {
            return null;
        }
        return new HotReloadState(kind, pkg, savedProcessName, classLoader,
            appInfo, firstPackage, appContext);
    }

    private record HotReloadState(
        @NonNull String kind,
        @NonNull String packageName,
        @Nullable String processName,
        @NonNull ClassLoader classLoader,
        @Nullable ApplicationInfo applicationInfo,
        boolean firstPackage,
        @Nullable Context appContext
    ) {
    }

    private static final class RestoredPackageReadyParam implements PackageReadyParam {
        private final String packageName;
        private final ClassLoader classLoader;
        @Nullable
        private final ApplicationInfo applicationInfo;
        private final boolean isFirstPackage;

        RestoredPackageReadyParam(@NonNull String packageName, @NonNull ClassLoader classLoader,
                                  @Nullable ApplicationInfo applicationInfo, boolean isFirstPackage) {
            this.packageName = packageName;
            this.classLoader = classLoader;
            this.applicationInfo = applicationInfo;
            this.isFirstPackage = isFirstPackage;
        }

        @NonNull
        @Override
        public ClassLoader getClassLoader() {
            return classLoader;
        }

        @NonNull
        @Override
        public AppComponentFactory getAppComponentFactory() {
            throw new UnsupportedOperationException(
                "AppComponentFactory is unavailable in hot reload context");
        }

        @NonNull
        @Override
        public String getPackageName() {
            return packageName;
        }

        @NonNull
        @Override
        public ApplicationInfo getApplicationInfo() {
            if (applicationInfo == null) {
                throw new UnsupportedOperationException(
                    "ApplicationInfo unavailable in hot reload context (system_server or missing snapshot)");
            }
            return applicationInfo;
        }

        @Override
        public boolean isFirstPackage() {
            return isFirstPackage;
        }

        @NonNull
        @Override
        public ClassLoader getDefaultClassLoader() {
            return classLoader;
        }
    }

    private static final class RestoredSystemServerParam implements SystemServerStartingParam {
        private final ClassLoader classLoader;

        RestoredSystemServerParam(@NonNull ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @NonNull
        @Override
        public ClassLoader getClassLoader() {
            return classLoader;
        }
    }

    protected void invokeInit(PackageReadyParam lpparam) {
        invokeInitInternal(lpparam.getPackageName(), module -> module.onLoad(lpparam));
    }

    protected void invokeInit(SystemServerStartingParam lpparam) {
        invokeInitInternal(BaseLoad.SYSTEM_SERVER, module -> module.onLoad(lpparam));
    }

    private void loadSystemEntryHooks(SystemServerStartingParam lpparam) {
        try {
            new CrashMonitor(lpparam);
        } catch (Exception e) {
            XposedLog.e(TAG, "system", "Crash Hook load failed, " + e);
        }

        if (PrefsBridge.getBoolean("system_framework_core_patch_enable")) {
            new CorePatch().onLoad(lpparam);
            XposedLog.d(TAG, "system", "CorePatch loaded");
        }
        if (PrefsBridge.getBoolean("system_other_flag_secure")) {
            new FlagSecure().onLoad(lpparam);
            XposedLog.d(TAG, "system", "FlagSecure loaded");
        }
    }

    private void invokeInitInternal(String packageName, ModuleLoader loader) {
        HashMap<String, DataBase> dataMap = DataBase.get();

        ClassLoader classLoader = getClass().getClassLoader();
        if (classLoader == null) {
            XposedLog.e(TAG, "ClassLoader is null, skip loading modules for: " + packageName);
            return;
        }

        ModuleMatcher.MatchContext context = buildMatchContext(packageName, dataMap);
        ModuleMatcher matcher = new ModuleMatcher(context);

        dataMap.forEach((className, data) -> {
            if (!matcher.shouldLoad(data, packageName)) return;
            try {
                Class<?> clazz = classLoader.loadClass(className);
                BaseLoad module = (BaseLoad) clazz.getDeclaredConstructor().newInstance();
                loader.load(module);
            } catch (ReflectiveOperationException e) {
                XposedLog.e(TAG, "Failed to load module: " + className, e);
            }
        });
    }

    private ModuleMatcher.MatchContext buildMatchContext(String packageName, HashMap<String, DataBase> dataMap) {
        boolean isSystemServer = BaseLoad.SYSTEM_SERVER.equals(packageName);
        boolean hasExactMatch = dataMap.values().stream()
            .anyMatch(data -> packageName.equals(data.targetPackage));

        return ModuleMatcher.MatchContext.builder()
            .systemServer(isSystemServer)
            .exactMatch(hasExactMatch)
            .debugMode(PrefsBridge.getBoolean("development_debug_mode"))
            .build();
    }

    @FunctionalInterface
    private interface ModuleLoader {
        void load(BaseLoad module);
    }

    protected void initPrefs() {
        if (prefsInited) {
            return;
        }
        synchronized (prefsInitLock) {
            if (prefsInited) {
                return;
            }
            PrefsBridge.initForHook(getRemotePreferences(PrefsBridge.REMOTE_PREFS_GROUP));
            LogStatusManager.syncLogLevelFromPrefs();
            prefsInited = true;
        }
    }

    private boolean prepareHookLoad(String packageName) {
        if (!isHookEnabled()) {
            XposedLog.w(TAG, packageName, "Skip loading hooks because hook loading is disabled by app state.");
            return true;
        }
        return false;
    }

    private void attachHookLogLevelObserver(boolean isSystem) {
        ContextUtils.getWaitContext(context -> {
            if (context != null) {
                LogStatusManager.attachHookLogLevelObserver(context);
            }
        }, isSystem);
    }

    private boolean isHookEnabled() {
        return PrefsBridge.getBoolean(PREF_ALLOW_HOOK, false)
            && isFrameworkAllowedForCurrentRuntime();
    }

    private boolean isFrameworkAllowedForCurrentRuntime() {
        boolean allowHook = PrefsBridge.getBoolean(PREF_FRAMEWORK_ALLOW_HOOK, true);
        if (allowHook) {
            return true;
        }

        String storedReason = PrefsBridge.getString(PREF_FRAMEWORK_REASON, null);
        if (TextUtils.isEmpty(storedReason)) {
            return false;
        }

        String currentFrameworkName;
        String currentFrameworkVersion;
        long currentFrameworkVersionCode;
        try {
            currentFrameworkName = normalizeFrameworkText(getFrameworkName());
            currentFrameworkVersion = normalizeFrameworkText(getFrameworkVersion());
            currentFrameworkVersionCode = getFrameworkVersionCode();
        } catch (Throwable t) {
            return false;
        }

        String checkedFrameworkName = normalizeFrameworkText(PrefsBridge.getString(PREF_FRAMEWORK_NAME, null));
        String checkedFrameworkVersion = normalizeFrameworkText(PrefsBridge.getString(PREF_FRAMEWORK_VERSION, null));
        long checkedFrameworkVersionCode = PrefsBridge.getLong(PREF_FRAMEWORK_VERSION_CODE, Long.MIN_VALUE);

        boolean matched = Objects.equals(currentFrameworkName, checkedFrameworkName)
            && Objects.equals(currentFrameworkVersion, checkedFrameworkVersion)
            && currentFrameworkVersionCode == checkedFrameworkVersionCode;
        if (matched) {
            return false;
        }

        XposedLog.i(
            TAG,
            processName,
            "Allow loading hooks temporarily because the running framework differs from the last framework blocked by XposedService."
        );
        return true;
    }

    private static String normalizeFrameworkText(String value) {
        if (value == null) {
            return null;
        }
        String trimValue = value.trim();
        return trimValue.isEmpty() ? null : trimValue;
    }
}
