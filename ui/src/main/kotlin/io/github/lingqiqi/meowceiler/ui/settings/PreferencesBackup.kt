package io.github.lingqiqi.meowceiler.ui.settings

import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.meowui.core.preference.PreferenceStore
import io.github.lingqiqi5211.meowui.core.preference.PreferenceType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置的导出与导入，按 [Preferences.all] 走。
 *
 * 只认契约里声明过的键：文件里多出来的键一概忽略，别的版本或手改过的文件灌不进来。
 * 反过来，文件里缺的键保持当前值不动 —— 导入是合并，不是整表替换。
 */
object PreferencesBackup {
    const val MimeType = "application/json"
    const val FileName = "meowceiler-settings.json"

    /** 文件格式版本。键的类型变了才需要动它，加键不用。 */
    private const val Version = 1
    private const val FieldVersion = "version"
    private const val FieldValues = "values"

    fun export(store: PreferenceStore): String {
        val values = JSONObject()
        Preferences.all.forEach { key -> values.put(key.name, store.readAsJson(key)) }
        return JSONObject()
            .put(FieldVersion, Version)
            .put(FieldValues, values)
            .toString(2)
    }

    /** 返回实际写入的条目数；文件读不懂时返回 null。 */
    suspend fun import(store: PreferenceStore, text: String): Int? {
        val values = runCatching { JSONObject(text).getJSONObject(FieldValues) }.getOrNull()
            ?: return null
        var applied = 0
        Preferences.all.forEach { key ->
            val raw = values.opt(key.name) ?: return@forEach
            if (store.writeFromJson(key, raw)) applied++
        }
        return applied
    }

    /** 类型对不上就跳过这一条，返回 false —— 半个错值比缺一项更难查。 */
    @Suppress("UNCHECKED_CAST")
    private fun PreferenceStore.readAsJson(key: PreferenceKey<*>): Any = when (key.type) {
        PreferenceType.Boolean -> read(key as PreferenceKey<Boolean>)
        PreferenceType.Int -> read(key as PreferenceKey<Int>)
        PreferenceType.Long -> read(key as PreferenceKey<Long>)
        PreferenceType.Float -> read(key as PreferenceKey<Float>).toDouble()
        PreferenceType.String -> read(key as PreferenceKey<String>)
        PreferenceType.StringSet -> JSONArray(read(key as PreferenceKey<Set<String>>).toList())
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun PreferenceStore.writeFromJson(key: PreferenceKey<*>, raw: Any): Boolean {
        when (key.type) {
            PreferenceType.Boolean ->
                write(key as PreferenceKey<Boolean>, raw as? Boolean ?: return false)

            PreferenceType.Int ->
                write(key as PreferenceKey<Int>, (raw as? Number)?.toInt() ?: return false)

            PreferenceType.Long ->
                write(key as PreferenceKey<Long>, (raw as? Number)?.toLong() ?: return false)

            PreferenceType.Float ->
                write(key as PreferenceKey<Float>, (raw as? Number)?.toFloat() ?: return false)

            PreferenceType.String ->
                write(key as PreferenceKey<String>, raw as? String ?: return false)

            PreferenceType.StringSet -> {
                val array = raw as? JSONArray ?: return false
                val set = (0 until array.length()).mapTo(linkedSetOf()) { array.getString(it) }
                write(key as PreferenceKey<Set<String>>, set)
            }
        }
        return true
    }
}
