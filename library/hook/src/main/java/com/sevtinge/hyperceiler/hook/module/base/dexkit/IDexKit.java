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
<<<<<<<< HEAD:library/hook/src/main/java/com/sevtinge/hyperceiler/hook/module/base/dexkit/IDexKit.java
package com.sevtinge.hyperceiler.hook.module.base.dexkit;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.result.base.BaseData;

/**
 * @author 焕晨HChen
 */
public interface IDexKit {
    BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException;
========
package com.sevtinge.hyperceiler.home.helper;

import com.sevtinge.hyperceiler.R;
import com.sevtinge.hyperceiler.dashboard.SettingsPreferenceFragment;

public class HelpFragment extends SettingsPreferenceFragment {

    @Override
    public int getPreferenceScreenResId() {
        return R.xml.prefs_help;
    }

>>>>>>>> 95be19db1 (refactor: new ui (#1500)):app/src/main/java/com/sevtinge/hyperceiler/home/helper/HelpFragment.java
}
