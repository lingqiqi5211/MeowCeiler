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

package com.sevtinge.hyperceiler.libhook.rules.systemsettings;

import static com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.AppsTool.getModuleRes;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.sevtinge.hyperceiler.libhook.R;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.util.List;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

public class MoreVpnTypes extends BaseHook {
    @Override
    public void init() {
        findAndHookMethod("com.android.internal.net.VpnProfile", "isLegacyType", int.class, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                int type = (int) param.getArgs()[0];
                if (type >= 0 && type <= 5) {
                    param.setResult(true);
                }
            }
        });

        Class<?> editFragment = findClassIfExists("com.android.settings.vpn2.MiuiVpnEditFragment");
        Class<?> configDialog = findClassIfExists("com.android.settings.vpn2.ConfigDialog");
        Class<?> miuiSpinner = findClassIfExists("miuix.appcompat.widget.Spinner");
        if (!canSetVpnTypes(editFragment) || !canSetVpnTypes(configDialog)) return;
        if (!canHookTypeSpinner(editFragment, miuiSpinner)
            || !canHookTypeSpinner(configDialog, Spinner.class)) return;
        if (editFragment == null && configDialog == null) return;

        setVpnTypes(editFragment);
        setVpnTypes(configDialog);
        hookTypeSpinner(editFragment, miuiSpinner, true);
        hookTypeSpinner(configDialog, Spinner.class, false);
    }

    private boolean canSetVpnTypes(Class<?> clazz) {
        return clazz == null || findFieldIfExists(clazz, "VPN_TYPES") != null;
    }

    private void setVpnTypes(Class<?> clazz) {
        if (clazz != null) {
            setStaticObjectField(clazz, "VPN_TYPES", List.of(0, 1, 2, 3, 4, 5, 6, 7, 8));
        }
    }

    private boolean canHookTypeSpinner(Class<?> clazz, Class<?> spinnerClass) {
        return clazz == null || (spinnerClass != null
            && findMethodExactIfExists(clazz, "setTypesByFeature", spinnerClass) != null);
    }

    private void hookTypeSpinner(Class<?> clazz, Class<?> spinnerClass, boolean useMiuiLayout) {
        if (clazz == null) return;

        findAndHookMethod(clazz, "setTypesByFeature", spinnerClass, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                if (!(param.getArgs()[0] instanceof Spinner spinner)) return;

                Context context = (Context) callMethod(param.getThisObject(), "getContext");
                String[] vpnTypes = getModuleRes(context).getStringArray(
                    R.array.hook_system_settings_vpn_types);

                ArrayAdapter<String> adapter;
                if (useMiuiLayout) {
                    int itemLayout = context.getResources().getIdentifier(
                        "miuix_appcompat_simple_spinner_layout_integrated", "layout", "com.android.settings");
                    int dropDownLayout = context.getResources().getIdentifier(
                        "miuix_appcompat_simple_spinner_dropdown_item", "layout", "com.android.settings");
                    if (itemLayout == 0 || dropDownLayout == 0) {
                        itemLayout = android.R.layout.simple_spinner_item;
                        dropDownLayout = android.R.layout.simple_spinner_dropdown_item;
                    }
                    adapter = new ArrayAdapter<>(context, itemLayout, android.R.id.text1, vpnTypes);
                    adapter.setDropDownViewResource(dropDownLayout);
                } else {
                    adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, vpnTypes);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                }
                spinner.setAdapter(adapter);
            }
        });
    }
}
