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

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.home.gesture;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;

import com.sevtinge.hyperceiler.libhook.appbase.mihome.HomeBaseHookNew;
import com.sevtinge.hyperceiler.libhook.appbase.mihome.Version;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import com.sevtinge.hyperceiler.libhook.utils.api.ShakeManager;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class ShakeDevice extends HomeBaseHookNew {

    private static final String STATE_LAUNCHER_ACTIVITY = "ShakeDevice.launcherActivity";
    private static final String SHAKE_MGR_KEY = "MIUIZER_SHAKE_MGR";
    private static final String SENSOR_CLEANUP_KEY = "MIUIZER_SHAKE_SENSOR_CLEANUP";

    @Version(isPad = false, min = 600000000)
    private void initOS3Hook() {
        restoreShakeListener();

        findAndHookMethod("com.miui.home.launcher.BaseLauncher", "onResume", new IMethodHook() {
            @Override
            public void after(HookParam param) {
                registerShakeListener((Activity) param.getThisObject());
            }
        });

        findAndHookMethod("com.miui.home.launcher.BaseLauncher", "onPause", new IMethodHook() {
            @Override
            public void after(HookParam param) {
                unregisterShakeListener((Activity) param.getThisObject());
            }
        });
    }

    @Override
    public void initBase() {
        restoreShakeListener();

        findAndHookMethod("com.miui.home.launcher.Launcher", "onResume", new IMethodHook() {
            @Override
            public void after(HookParam param) {
                registerShakeListener((Activity) param.getThisObject());
            }
        });

        findAndHookMethod("com.miui.home.launcher.Launcher", "onPause", new IMethodHook() {
            @Override
            public void after(HookParam param) {
                unregisterShakeListener((Activity) param.getThisObject());
            }
        });
    }

    private void restoreShakeListener() {
        Activity activity = getHotReloadRuntimeState(STATE_LAUNCHER_ACTIVITY, Activity.class);
        if (activity != null) {
            registerShakeListener(activity);
        }
    }

    private void registerShakeListener(Activity launcherActivity) {
        Object stored = BaseHook.getAdditionalInstanceField(launcherActivity, SHAKE_MGR_KEY);
        ShakeManager shakeMgr = stored instanceof ShakeManager
            ? (ShakeManager) stored : new ShakeManager(launcherActivity);
        BaseHook.setAdditionalInstanceField(launcherActivity, SHAKE_MGR_KEY, shakeMgr);
        SensorManager sensorMgr = (SensorManager) launcherActivity.getSystemService(
            Context.SENSOR_SERVICE
        );
        shakeMgr.reset();
        sensorMgr.registerListener(
            shakeMgr,
            sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_NORMAL
        );
        // onResume 会反复走到这里，旧的注销项必须先释放，否则清理队列会随恢复次数无限增长。
        releaseSensorCleanup(launcherActivity);
        BaseHook.setAdditionalInstanceField(
            launcherActivity,
            SENSOR_CLEANUP_KEY,
            BaseHook.registerSensorListenerHotReloadCleanup(sensorMgr, shakeMgr)
        );
        putHotReloadRuntimeState(STATE_LAUNCHER_ACTIVITY, launcherActivity);
    }

    private void unregisterShakeListener(Activity launcherActivity) {
        Object stored = BaseHook.getAdditionalInstanceField(launcherActivity, SHAKE_MGR_KEY);
        if (stored instanceof ShakeManager shakeMgr) {
            SensorManager sensorMgr = (SensorManager) launcherActivity.getSystemService(
                Context.SENSOR_SERVICE
            );
            sensorMgr.unregisterListener(shakeMgr);
        }
        releaseSensorCleanup(launcherActivity);
        BaseHook.removeAdditionalInstanceField(launcherActivity, SHAKE_MGR_KEY);
        putHotReloadRuntimeState(STATE_LAUNCHER_ACTIVITY, null);
    }

    /** 注销后不再需要热重载清理，及时移除队列对监听器和宿主 Activity 的持有。 */
    private void releaseSensorCleanup(Activity launcherActivity) {
        Object stored = BaseHook.getAdditionalInstanceField(launcherActivity, SENSOR_CLEANUP_KEY);
        if (stored instanceof BaseHook.HotReloadCleanup cleanup) {
            cleanup.cancel();
            BaseHook.removeAdditionalInstanceField(launcherActivity, SENSOR_CLEANUP_KEY);
        }
    }
}
