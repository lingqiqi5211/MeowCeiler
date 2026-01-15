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
package com.sevtinge.hyperceiler.libhook.rules.systemsettings;

import android.app.NotificationChannel;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.callback.IMethodHook;
import com.sevtinge.hyperceiler.libhook.utils.log.XposedLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import io.github.kyuubiran.ezxhelper.xposed.common.AfterHookParam;
import io.github.kyuubiran.ezxhelper.xposed.common.BeforeHookParam;

public class MoreNotificationSettings extends BaseHook {

    Class<?> mBaseNotificationSettings;
    Class<?> mChannelNotificationSettings;

    @Override
    public void init() {
        mBaseNotificationSettings = findClassIfExists("com.android.settings.notification.BaseNotificationSettings");
        mChannelNotificationSettings = findClassIfExists("com.android.settings.notification.ChannelNotificationSettings");


        hookAllMethods(mBaseNotificationSettings, "setPrefVisible", new IMethodHook() {
            @Override
            public void before(BeforeHookParam param) {
                Object pref = param.getArgs()[0];
                if (pref != null) {
                    String prefKey = (String) callMethod(pref, "getKey");
                    if ("importance".equals(prefKey) || "badge".equals(prefKey) || "allow_keyguard".equals(prefKey)) {
                        param.getArgs()[1] = true;
                    }
                }
            }
        });

        hookAllMethods("com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow", "onClickInfoItem", new IMethodHook() {
            @Override
            public void before(BeforeHookParam param) {
                Context mContext = (Context)param.getArgs()[0];
                Object entry = callMethod(getObjectField(param.getThisObject(), "mParent"), "getEntry");
                String id = (String)callMethod(callMethod(entry, "getChannel"), "getId");
                if ("miscellaneous".equals(id)) return;
                Object notification = callMethod(entry, "getSbn");
                Class<?> nuCls = findClassIfExists("com.android.systemui.miui.statusbar.notification.NotificationUtil", getClassLoader());
                if (nuCls != null) {
                    boolean isHybrid = (boolean)callStaticMethod(nuCls, "isHybrid", notification);
                    if (isHybrid) return;
                }
                Intent intent = getIntent(notification, id);
                try {
                    callMethod(mContext, "startActivityAsUser", intent, android.os.Process.myUserHandle());
                    param.setResult(null);
                    Object ModalController = callStaticMethod(findClass("com.android.systemui.Dependency", mContext.getClassLoader()), "get", findClass("com.android.systemui.statusbar.notification.modal.ModalController", mContext.getClassLoader()));
                    callMethod(ModalController, "animExitModelCollapsePanels");
                } catch (Throwable ignore) {
                    XposedLog.e(TAG, getPackageName(), "onClickInfoItem hook error", ignore);
                }
            }

            @NonNull
            private static Intent getIntent(Object notification, String id) {
                String pkgName = (String) callMethod(notification, "getPackageName");
                int user = (int) callMethod(notification, "getAppUid");

                Bundle bundle = new Bundle();
                bundle.putString("android.provider.extra.CHANNEL_ID", id);
                bundle.putString("package", pkgName);
                bundle.putInt("uid", user);
                bundle.putString("miui.targetPkg", pkgName);
                Intent intent = new Intent("android.intent.action.MAIN");
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(":android:show_fragment", "com.android.settings.notification.ChannelNotificationSettings");
                intent.putExtra(":android:show_fragment_args", bundle);
                intent.setClassName("com.android.settings", "com.android.settings.SubSettings");
                return intent;
            }
        });

        findAndHookMethod(mChannelNotificationSettings, "setupChannelDefaultPrefs", new IMethodHook() {
            @Override
            public void after(AfterHookParam param) {
                Object pref = callMethod(param.getThisObject(), "findPreference", "importance");
                setObjectField(param.getThisObject(), "mImportance", pref);
                int mBackupImportance = (int) getObjectField(param.getThisObject(), "mBackupImportance");
                if (mBackupImportance > 0) {
                    int index = (int) callMethod(pref, "findSpinnerIndexOfValue", String.valueOf(mBackupImportance));
                    if (index > -1) {
                        callMethod(pref, "setValueIndex", index);
                    }
                    Class<?> ImportanceListener = findClassIfExists("androidx.preference.Preference$OnPreferenceChangeListener", getClassLoader());
                    InvocationHandler handler = new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if (method.getName().equals("onPreferenceChange")) {
                                int mBackupImportance = Integer.parseInt((String) args[1]);
                                setObjectField(param.getThisObject(), "mBackupImportance", mBackupImportance);
                                NotificationChannel mChannel = (NotificationChannel) getObjectField(param.getThisObject(), "mChannel");
                                mChannel.setImportance(mBackupImportance);
                                callMethod(mChannel, "lockFields", 4);
                                Object mBackend = getObjectField(param.getThisObject(), "mBackend");
                                String mPkg = (String) getObjectField(param.getThisObject(), "mPkg");
                                int mUid = (int) getObjectField(param.getThisObject(), "mUid");
                                callMethod(mBackend, "updateChannel", mPkg, mUid, mChannel);
                                callMethod(param.getThisObject(), "updateDependents", false);
                            }
                            return true;
                        }
                    };
                    Object mImportanceListener = Proxy.newProxyInstance(
                        getClassLoader(),
                        new Class[]{ImportanceListener},
                        handler
                    );
                    callMethod(pref, "setOnPreferenceChangeListener", mImportanceListener);
                }
            }
        });
    }
}
