package io.github.lingqiqi.meowceiler

import android.os.Bundle
import android.os.SystemClock
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.ui.MeowCeilerApp
import io.github.lingqiqi.meowceiler.ui.R
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
    private var lastNotConnectedToast = 0L

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

    /** 节流显示写入失败提示，避免连续拖动时重复提示。 */
    private fun onWriteResult(result: PreferenceWriteResult) {
        when (result) {
            PreferenceWriteResult.Success -> Unit
            is PreferenceWriteResult.NotConnected -> reportNotConnected()
            is PreferenceWriteResult.Failure -> result.cause.printStackTrace()
        }
    }

    private fun reportNotConnected() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotConnectedToast < NotConnectedToastInterval) return
        lastNotConnectedToast = now
        Toast.makeText(this, R.string.preference_not_connected, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val NotConnectedToastInterval = 3_000L
    }
}
