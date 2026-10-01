package io.github.lingqiqi.meowceiler.shared

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** 发现的应用是模块本地数据；宿主远程偏好只读，不能用来回传应用列表。 */
object FocusAppRegistry {
    const val MethodRecord = "record_focus_app"
    const val KeyKind = "kind"
    const val KeyAccepted = "accepted"
    const val Notification = "notification"
    const val Media = "media"
    private const val FileName = "focus_apps"
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    private fun preferences(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(FileName, Context.MODE_PRIVATE)

    fun isValid(
        kind: String?,
        packageName: String?,
    ): Boolean =
        (kind == Notification || kind == Media) &&
            packageName != null && packageName.length <= 255 && packagePattern.matches(packageName)

    @Synchronized
    fun record(
        context: Context,
        kind: String,
        packageName: String,
    ): Boolean {
        require(isValid(kind, packageName))
        val preferences = preferences(context)
        val current = preferences.getStringSet(kind, emptySet()).orEmpty()
        return preferences.edit().putStringSet(kind, current + packageName).commit()
    }

    fun observe(
        context: Context,
        kind: String,
    ) = callbackFlow {
        require(kind == Notification || kind == Media)
        val preferences = preferences(context)

        fun publish() {
            trySend(preferences.getStringSet(kind, emptySet()).orEmpty().toSet())
        }
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == kind) publish()
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        publish()
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
