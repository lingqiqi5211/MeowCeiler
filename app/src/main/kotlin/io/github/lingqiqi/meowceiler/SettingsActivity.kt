package io.github.lingqiqi.meowceiler

import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.ui.MeowCeilerApp
import io.github.lingqiqi5211.meowui.core.preference.PreferenceWriteResult
import io.github.lingqiqi5211.meowui.xposed.setMeowXposedContent

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setMeowXposedContent(
            preferenceName = Preferences.NAME,
            onWriteResult = ::onWriteResult,
        ) {
            MeowCeilerApp(bridge = XposedFrameworkBridge)
        }
    }

    /** 写入失败必须暴露，不能吞掉或伪装成功。 */
    private fun onWriteResult(result: PreferenceWriteResult) {
        when (result) {
            PreferenceWriteResult.Success -> Unit
            is PreferenceWriteResult.NotConnected -> Unit
            is PreferenceWriteResult.Failure -> result.cause.printStackTrace()
        }
    }
}
