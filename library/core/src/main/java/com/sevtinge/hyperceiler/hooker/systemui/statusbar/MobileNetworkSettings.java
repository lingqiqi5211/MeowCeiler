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
package com.sevtinge.hyperceiler.hooker.systemui.statusbar;

import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreSmallVersion;

import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;

import fan.preference.DropDownPreference;

public class MobileNetworkSettings  extends DashboardFragment {

    DropDownPreference mNewHD;
    DropDownPreference mSmallHD;
    DropDownPreference mBigHD;
    DropDownPreference mHideNoSIM;
    SwitchPreference mHideCard1;
    SwitchPreference mHideCard2;
    Preference mDoubleMobile;

    @Override
    public int getPreferenceScreenResId() {
        return R.xml.system_ui_status_bar_mobile;
    }

    @Override
    public void initPrefs() {
        mSmallHD = findPreference("prefs_key_system_ui_status_bar_icon_small_hd");
        mBigHD = findPreference("prefs_key_system_ui_status_bar_icon_big_hd");
        mNewHD = findPreference("prefs_key_system_ui_status_bar_icon_new_hd");
        mHideNoSIM = findPreference("prefs_key_system_ui_status_bar_icon_mobile_network_signal_no_card");
        mHideCard1 = findPreference("prefs_key_system_ui_status_bar_icon_mobile_network_hide_card_1");
        mHideCard2 = findPreference("prefs_key_system_ui_status_bar_icon_mobile_network_hide_card_2");
        mDoubleMobile = findPreference("prefs_key_system_ui_statusbar_iconmanage_mobile_network");

        if (isMoreHyperOSVersion(3f)) {
            setPreVisible(mSmallHD, false);
            setPreVisible(mBigHD, false);
            setPreVisible(mNewHD, false);
        } else if (isMoreSmallVersion(200, 2f)) {
            setFuncHint(mSmallHD, 1);
            setFuncHint(mBigHD, 1);
            setFuncHint(mNewHD, 1);
        }

        if (mHideCard1.isChecked() || mHideCard2.isChecked()) {
            mDoubleMobile.setEnabled(false);
            mDoubleMobile.setSummary(R.string.system_ui_statusbar_iconmanage_nosupport_mobile_network);
        }

        mHideCard1.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean card1WillBeChecked = (boolean) newValue;
            boolean card2Checked = mHideCard2.isChecked();
            if (card1WillBeChecked || card2Checked) {
                mDoubleMobile.setEnabled(false);
                mDoubleMobile.setSummary(R.string.system_ui_statusbar_iconmanage_nosupport_mobile_network);
            } else {
                mDoubleMobile.setEnabled(true);
                mDoubleMobile.setSummary("");
            }
            return true;
        });

        mHideCard2.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean card1Checked = mHideCard1.isChecked();
            boolean card2WillBeChecked = (boolean) newValue;
            if (card1Checked || card2WillBeChecked) {
                mDoubleMobile.setEnabled(false);
                mDoubleMobile.setSummary(R.string.system_ui_statusbar_iconmanage_nosupport_mobile_network);
            } else {
                mDoubleMobile.setEnabled(true);
                mDoubleMobile.setSummary("");
            }
            return true;
        });


    }
}
