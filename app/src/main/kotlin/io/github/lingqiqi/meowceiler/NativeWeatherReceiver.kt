package io.github.lingqiqi.meowceiler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NativeWeatherReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!context.getSharedPreferences("native_runtime", Context.MODE_PRIVATE).getBoolean("configured", false)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NativeWeatherBridgeImpl(context.applicationContext).resume()
            } finally {
                pending.finish()
            }
        }
    }
}
