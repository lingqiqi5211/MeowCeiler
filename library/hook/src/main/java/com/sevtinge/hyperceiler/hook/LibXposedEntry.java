/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2025 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.hook;

import static com.sevtinge.hyperceiler.hook.module.base.tool.AppsTool.getPackageVersionCode;
import static com.sevtinge.hyperceiler.hook.module.base.tool.AppsTool.getPackageVersionName;
import static com.sevtinge.hyperceiler.hook.utils.devicesdk.MiDeviceAppUtilsKt.isPad;
import static com.sevtinge.hyperceiler.hook.utils.devicesdk.SystemSDKKt.getAndroidVersion;
import static com.sevtinge.hyperceiler.hook.utils.devicesdk.SystemSDKKt.getHyperOSVersion;
import static com.sevtinge.hyperceiler.hook.utils.devicesdk.SystemSDKKt.isAndroidVersion;
import static com.sevtinge.hyperceiler.hook.utils.devicesdk.SystemSDKKt.isHyperOSVersion;
import static com.sevtinge.hyperceiler.hook.utils.log.LogManager.logLevelDesc;
import static com.sevtinge.hyperceiler.hook.utils.log.XposedLogUtils.logE;
import static com.sevtinge.hyperceiler.hook.utils.log.XposedLogUtils.logI;
import static com.sevtinge.hyperceiler.hook.utils.prefs.PrefsUtils.mPrefsMap;

import android.os.Process;

import com.hchen.hooktool.HCInit;
import com.hchen.hooktool.utils.ResInjectTool;
import com.sevtinge.hyperceiler.hook.module.app.VariousThirdApps;
import com.sevtinge.hyperceiler.hook.module.base.BaseModule;
import com.sevtinge.hyperceiler.hook.module.base.tool.ResourcesTool;
import com.sevtinge.hyperceiler.hook.module.skip.SystemFrameworkForCorePatch;
import com.sevtinge.hyperceiler.hook.safe.CrashHook;
import com.sevtinge.hyperceiler.hook.utils.api.ProjectApi;
import com.sevtinge.hyperceiler.hook.utils.log.AndroidLogUtils;
import com.sevtinge.hyperceiler.hook.utils.log.LogManager;
import com.sevtinge.hyperceiler.hook.utils.prefs.PrefsUtils;
import com.sevtinge.hyperceiler.module.base.DataBase;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.kyuubiran.ezxhelper.android.logging.Logger;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class LibXposedEntry extends XposedModule {

    String processName;
    private static final String TAG = "HyperCeiler";
    public static String mModulePath = null;
    public static ResourcesTool mResHook;
    // public static XmlTool mXmlTool;
    public final VariousThirdApps mVariousThirdApps = new VariousThirdApps();

    public LibXposedEntry(final XposedInterface base, final XposedModuleInterface.ModuleLoadedParam param) {
        super(base, param);
        this.processName = param.getProcessName();
    }

    @Override
    public void onSystemServerLoaded(final SystemServerLoadedParam lpparam) {
        // load SharePrefs
        setSharedPrefs();
        // if (!mPrefsMap.getBoolean("allow_hook")) return;

        // load EzXHelper
        Logger.INSTANCE.setTag(TAG);

        // load HCInit
        HCInit.initBasicData(new HCInit.BasicData()
            .setModulePackageName(BuildConfig.APP_MODULE_ID)
            .setLogLevel(LogManager.getLogLevel())
            .setTag("HyperCeiler")
        );

        XC_LoadPackage.LoadPackageParam moduleParam = getSystemParam(lpparam); // Todo: 暂无兼容旧 api 的解决方法

        // load EzXHelper and set log tag
        EzXposed.initHandleLoadPackage(moduleParam);
        Logger.INSTANCE.setTag(TAG);
        logI("android", "androidVersion = " + getAndroidVersion() + ", hyperosVersion = " + getHyperOSVersion());

        // load CorePatch
        try {
            new SystemFrameworkForCorePatch().handleLoadPackage(moduleParam);
        } catch (Throwable e) {
            AndroidLogUtils.logE("SystemFrameworkForCorePatch", "HyperCeiler core patch load failed", e);
        }

        mModulePath = getApplicationInfo().sourceDir;

        // load ResourcesTool
        if (!mPrefsMap.getBoolean("module_settings_reshook_new")) {
            mResHook = new ResourcesTool(getApplicationInfo().sourceDir);
        } else {
            ResInjectTool.injectModuleRes();
        }

        // load Module hook
        androidCrashEventHook(moduleParam);
        invokeSystemInit(moduleParam);
    }

    @Override
    public void onPackageLoaded(final PackageLoadedParam lpparam) {
        if (isInSafeMode(lpparam.getPackageName()) || !lpparam.isFirstPackage()) return;
        // if (!mPrefsMap.getBoolean("allow_hook")) return;
        XC_LoadPackage.LoadPackageParam moduleParam = getParam(lpparam); // Todo: 暂无兼容旧 api 的解决方法

        // load EzXHelper and set log tag
        EzXposed.initHandleLoadPackage(moduleParam);
        Logger.INSTANCE.setTag(TAG);

        // load module
        String packageName = lpparam.getPackageName();
        logI(packageName, "versionName = " + getPackageVersionName(moduleParam) + ", versionCode = " + getPackageVersionCode(moduleParam));

        // load ResourcesTool
        ResInjectTool.injectModuleRes();
        // load Module hook
        invokeInit(moduleParam);
    }

    private void setSharedPrefs() {
        // Todo：迁移到 getRemotePreferences
        if (mPrefsMap.isEmpty()) {
            /*SharedPreferences readPrefs = getRemotePreferences(PrefsUtils.mPrefsName + "_remote");
            Map<String, ?> allPrefs = readPrefs.getAll();
            if (allPrefs == null || allPrefs.size() == 0)
                logE("UID" + Process.myUid(), "Cannot read SharedPreferences, some mods might not work!");
            else
                mPrefsMap.putAll(allPrefs);*/

            try {
                XSharedPreferences mXSharedPreferences = new XSharedPreferences(ProjectApi.mAppModulePkg, PrefsUtils.mPrefsName);
                mXSharedPreferences.makeWorldReadable();
                Map<String, ?> allPrefs = mXSharedPreferences.getAll();

                if (allPrefs == null || allPrefs.isEmpty()) {
                    mXSharedPreferences = new XSharedPreferences(new File(PrefsUtils.mPrefsFile));
                    mXSharedPreferences.makeWorldReadable();
                    allPrefs = mXSharedPreferences.getAll();
                }

                if (allPrefs != null && !allPrefs.isEmpty()) {
                    mPrefsMap.putAll(allPrefs);
                } else {
                    logE("UID" + Process.myUid(), "Cannot read SharedPreferences, some mods might not work!");
                }
            } catch (Throwable t) {
                logE("setXSharedPrefs", t);
            }
        }


    }

    private XC_LoadPackage.LoadPackageParam getSystemParam(SystemServerLoadedParam lpparam) {
        XC_LoadPackage.LoadPackageParam xposedParam;
        try {
            Class<?> cSet = Class.forName("de.robv.android.xposed.XposedBridge$CopyOnWriteSortedSet");
            Object emptySet = cSet.getConstructor().newInstance();
            java.lang.reflect.Constructor<XC_LoadPackage.LoadPackageParam> constructor =
                XC_LoadPackage.LoadPackageParam.class.getDeclaredConstructor(cSet);
            constructor.setAccessible(true);
            xposedParam = constructor.newInstance(emptySet);
        } catch (Exception e) {
            throw new RuntimeException("Cannot be instantiated LoadPackageParam", e);
        }
        xposedParam.classLoader = lpparam.getClassLoader();

        return xposedParam;
    }

    private XC_LoadPackage.LoadPackageParam getParam(PackageLoadedParam lpparam) {
        XC_LoadPackage.LoadPackageParam xposedParam;
        try {
            Class<?> cSet = Class.forName("de.robv.android.xposed.XposedBridge$CopyOnWriteSortedSet");
            Object emptySet = cSet.getConstructor().newInstance();
            java.lang.reflect.Constructor<XC_LoadPackage.LoadPackageParam> constructor =
                XC_LoadPackage.LoadPackageParam.class.getDeclaredConstructor(cSet);
            constructor.setAccessible(true);
            xposedParam = constructor.newInstance(emptySet);
        } catch (Exception e) {
            throw new RuntimeException("Cannot be instantiated LoadPackageParam", e);
        }
        xposedParam.packageName = lpparam.getPackageName();
        xposedParam.classLoader = lpparam.getClassLoader();
        xposedParam.appInfo = lpparam.getApplicationInfo();
        xposedParam.isFirstApplication = lpparam.isFirstPackage();

        return xposedParam;
    }

    private void invokeSystemInit(XC_LoadPackage.LoadPackageParam lpparam) {
        HashMap<String, DataBase> dataMap = DataBase.get();

        dataMap.forEach(new BiConsumer<>() {
            @Override
            public void accept(String s, DataBase dataBase) {
                if (!(dataBase.mTargetSdk == -1) && !isAndroidVersion(dataBase.mTargetSdk))
                    return;
                if (!(dataBase.mTargetOSVersion == -1F) && !(isHyperOSVersion(dataBase.mTargetOSVersion)))
                    return;
                if ((dataBase.isPad == 1 && !isPad()) || (dataBase.isPad == 2 && isPad()))
                    return;

                try {
                    Class<?> clazz = Objects.requireNonNull(getClass().getClassLoader()).loadClass(s);
                    BaseModule module = (BaseModule) clazz.getDeclaredConstructor().newInstance();
                    module.init(lpparam);
                } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                         InstantiationException | InvocationTargetException e) {
                    logE(TAG, e);
                }
            }
        });
    }

    private void invokeInit(XC_LoadPackage.LoadPackageParam lpparam) {
        String mPkgName = lpparam.packageName;
        if (mPkgName == null) return;
        if (isOtherRestrictions(mPkgName)) return;

        HashMap<String, DataBase> dataMap = DataBase.get();
        if (dataMap.values().stream().noneMatch(dataBase -> dataBase.mTargetPackage.equals(mPkgName))) {
            mVariousThirdApps.init(lpparam);
            return;
        }

        dataMap.forEach(new BiConsumer<>() {
            @Override
            public void accept(String s, DataBase dataBase) {
                if (!mPkgName.equals(dataBase.mTargetPackage))
                    return;
                if (!(dataBase.mTargetSdk == -1) && !isAndroidVersion(dataBase.mTargetSdk))
                    return;
                if (!(dataBase.mTargetOSVersion == -1F) && !(isHyperOSVersion(dataBase.mTargetOSVersion)))
                    return;
                if ((dataBase.isPad == 1 && !isPad()) || (dataBase.isPad == 2 && isPad()))
                    return;

                try {
                    Class<?> clazz = Objects.requireNonNull(getClass().getClassLoader()).loadClass(s);
                    BaseModule module = (BaseModule) clazz.getDeclaredConstructor().newInstance();
                    module.init(lpparam);
                } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                         InstantiationException | InvocationTargetException e) {
                    logE(TAG, e);
                }
            }
        });
    }

    private void androidCrashEventHook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("[HyperCeiler][I]: Log level is " + logLevelDesc());
        try {
            new CrashHook(lpparam);
        } catch (Exception e) {
            logE(TAG, e);
        }
    }

    private boolean isInSafeMode(String pkg) {
        switch (pkg) {
            case "com.android.systemui" -> {
                return isSafeModeEnable("system_ui_safe_mode_enable");
            }
            case "com.miui.home" -> {
                return isSafeModeEnable("home_safe_mode_enable");
            }
            case "com.miui.securitycenter" -> {
                return isSafeModeEnable("security_center_safe_mode_enable");
            }
        }
        return false;
    }

    private boolean isOtherRestrictions(String pkg) {
        switch (pkg) {
            case "com.google.android.webview", "com.miui.contentcatcher",
                 "com.miui.catcherpatch" -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean isSafeModeEnable(String key) {
        return mPrefsMap.getBoolean(key);
    }
}
