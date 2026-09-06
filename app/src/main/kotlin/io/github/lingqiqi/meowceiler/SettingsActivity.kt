package io.github.lingqiqi.meowceiler

import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.ui.MeowCeilerApp
import io.github.lingqiqi5211.meowui.core.preference.PreferenceWriteResult
import io.github.lingqiqi5211.meowui.core.preference.PreferenceConnectionState
import io.github.lingqiqi5211.meowui.libxposed.XposedServicePreferenceStore
import io.github.lingqiqi5211.meowui.preference.MeowPreferenceProvider
import io.github.lingqiqi5211.meowui.setMeowContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SettingsActivity : ComponentActivity() {
    private var preferenceStore: XposedServicePreferenceStore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = XposedServicePreferenceStore(Preferences.NAME)
        preferenceStore = store
        var ready = false
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!ready) return false
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        lifecycleScope.launch {
            // 远程偏好就绪后才创建主题，避免首帧从默认缩放动画到用户缩放。
            // 没装框架也能打开设置；只限制首次等待，不阻断后续连接。
            withTimeoutOrNull(1500) {
                store.connectionState.first { it == PreferenceConnectionState.Connected }
            }
            setMeowContent {
                MeowPreferenceProvider(store = store, onWriteResult = ::onWriteResult) {
                    MeowCeilerApp(bridge = XposedFrameworkBridge)
                }
            }
            ready = true
        }
    }

    override fun onDestroy() {
        preferenceStore?.close()
        super.onDestroy()
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
