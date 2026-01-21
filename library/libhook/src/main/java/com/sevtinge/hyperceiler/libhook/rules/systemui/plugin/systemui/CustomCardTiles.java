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
package com.sevtinge.hyperceiler.libhook.rules.systemui.plugin.systemui;

import static com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.EzxHelpUtils.findAndHookConstructor;
import static com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.EzxHelpUtils.findAndHookMethod;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import com.sevtinge.hyperceiler.libhook.callback.IMethodHook;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.EzxHelpUtils;
import com.sevtinge.hyperceiler.libhook.utils.log.XposedLog;

import java.util.List;

import io.github.kyuubiran.ezxhelper.xposed.common.AfterHookParam;
import io.github.kyuubiran.ezxhelper.xposed.common.BeforeHookParam;

public class CustomCardTiles {
    private static final String TAG = "CustomCardTiles";
    private static final String TAG_BT = "bt";
    private static final String TAG_CELL = "cell";
    private static final String TAG_FLASHLIGHT = "flashlight";
    private static final String TAG_VOWIFI1 = "vowifi1";
    private static final String TAG_VOWIFI2 = "vowifi2";
    private static final String TAG_WIFI = "wifi";

    private static int idEnable = -1;
    private static int idDisabled = -1;
    private static float cornerRadiusF = -1;

    public static void initCustomCardTiles(ClassLoader classLoader, List<String> cardStyleTiles) {
        hookGetCardStyleTileSpecs(classLoader, cardStyleTiles);
        hookQSCardItemViewConstructor(classLoader);
        hookUpdateBackground(classLoader);
        hookCreateVoWifiTiles(classLoader);
    }

    private static void hookGetCardStyleTileSpecs(ClassLoader classLoader, List<String> cardStyleTiles) {
        findAndHookMethod("miui.systemui.controlcenter.qs.QSController", classLoader,
            "getCardStyleTileSpecs",
            new GetCardStyleTileSpecsHook(cardStyleTiles));
    }

    private static void hookQSCardItemViewConstructor(ClassLoader classLoader) {
        findAndHookConstructor("miui.systemui.controlcenter.qs.tileview.QSCardItemView", classLoader,
            Context.class, AttributeSet.class,
            new QSCardItemViewConstructorHook());
    }

    private static void hookUpdateBackground(ClassLoader classLoader) {
        EzxHelpUtils.hookAllMethods("miui.systemui.controlcenter.qs.tileview.QSCardItemView", classLoader,
            "updateBackground",
            new UpdateBackgroundHook());
    }

    private static void hookCreateVoWifiTiles(ClassLoader classLoader) {
        findAndHookMethod("miui.systemui.controlcenter.panel.main.qs.QSCardsController", classLoader,
            "createVoWifiTiles",
            new CreateVoWifiTilesHook());
    }

    private record GetCardStyleTileSpecsHook(List<String> cardStyleTiles) implements IMethodHook {

        @Override
            public void before(BeforeHookParam param) {
                param.setResult(cardStyleTiles);
            }
        }

    private static class QSCardItemViewConstructorHook implements IMethodHook {
        @Override
        public void after(AfterHookParam param) {
            LinearLayout linearLayout = (LinearLayout) param.getThisObject();
            Context context = linearLayout.getContext();

            idEnable = context.getResources().getIdentifier(
                "qs_card_wifi_background_enabled", "drawable", "miui.systemui.plugin");
            idDisabled = context.getResources().getIdentifier(
                "qs_card_wifi_background_disabled", "drawable", "miui.systemui.plugin");

            int cornerRadius = context.getResources().getIdentifier(
                "control_center_universal_corner_radius", "dimen", "miui.systemui.plugin");
            cornerRadiusF = context.getResources().getDimensionPixelSize(cornerRadius);
        }
    }

    private static class UpdateBackgroundHook implements IMethodHook {
        @Override
        public void after(AfterHookParam param) {
            Object state = EzxHelpUtils.getObjectField(param.getThisObject(), "state");
            if (state == null) {
                return; // 系统界面组件会先 null 几次才会获取到值，应该是官方写法有问题
            }

            String spec = (String) EzxHelpUtils.getObjectField(state, "spec");
            int stateValue = EzxHelpUtils.getIntField(state, "state");

            // 跳过系统默认处理的 tile
            if (isSystemDefaultTile(spec)) {
                return;
            }

            updateTileBackground((LinearLayout) param.getThisObject(), stateValue);
        }

        private boolean isSystemDefaultTile(String spec) {
            return TAG_BT.equals(spec) || TAG_CELL.equals(spec) || TAG_FLASHLIGHT.equals(spec)
                || TAG_WIFI.equals(spec) || TAG_VOWIFI1.equals(spec) || TAG_VOWIFI2.equals(spec);
        }

        private void updateTileBackground(LinearLayout linearLayout, int stateValue) {
            if (idDisabled == -1 || idEnable == -1) {
                XposedLog.e(TAG, "id is -1!!");
                return;
            }

            Context context = linearLayout.getContext();Drawable drawable;

            if (stateValue == 2) {
                // 启用状态
                drawable = context.getTheme().getResources()
                    .getDrawable(idEnable, context.getTheme());
            } else if (stateValue == 1) {
                // 禁用状态
                drawable = context.getTheme().getResources()
                    .getDrawable(idDisabled, context.getTheme());
            } else {
                return;
            }

            linearLayout.setBackground(drawable);
            EzxHelpUtils.callMethod(linearLayout, "setCornerRadius", cornerRadiusF);
        }
    }

    private static class CreateVoWifiTilesHook implements IMethodHook {
        @Override
        public void before(BeforeHookParam param) {
            param.setResult(null);
        }
    }
}

