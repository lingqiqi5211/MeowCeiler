@file:OptIn(DexKitExperimentalApi::class)

package io.github.lingqiqi.meowceiler.hook.util

import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.io.File

/** DexKit 查询结果的磁盘缓存，命中时不创建原生桥。[stamp] 变了整份作废。 */
internal class DexKitCache(
    private val file: File,
    private val stamp: String,
) : DexKitCacheBridge.Cache {
    private val strings = LinkedHashMap<String, String>()
    private val lists = LinkedHashMap<String, List<String>>()

    @Volatile
    private var dirty = false

    init {
        load()
    }

    @Synchronized
    override fun getString(key: String, default: String?): String? = strings[key] ?: default

    @Synchronized
    override fun putString(key: String, value: String) {
        if (strings.put(key, value) != value) dirty = true
    }

    @Synchronized
    override fun getStringList(key: String, default: List<String>?): List<String>? =
        lists[key] ?: default

    @Synchronized
    override fun putStringList(key: String, value: List<String>) {
        if (lists.put(key, value) != value) dirty = true
    }

    @Synchronized
    override fun remove(key: String) {
        if (strings.remove(key) != null || lists.remove(key) != null) dirty = true
    }

    @Synchronized
    override fun getAllKeys(): Collection<String> = strings.keys + lists.keys

    @Synchronized
    override fun clearAll() {
        strings.clear()
        lists.clear()
        dirty = true
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        val root = JSONObject()
        root.put(KeyStamp, stamp)
        root.put(KeyStrings, JSONObject(strings as Map<*, *>))
        root.put(KeyLists, JSONObject(lists.mapValues { JSONArray(it.value) } as Map<*, *>))

        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                file.writeText(root.toString())
                temp.delete()
            }
            dirty = false
        }.onFailure { MLog.e("dexkit cache flush failed", it) }
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            if (root.optString(KeyStamp) != stamp) {
                MLog.d("dexkit cache stamp changed, dropping ${file.name}")
                return
            }
            root.optJSONObject(KeyStrings)?.let { node ->
                node.keys().forEach { key -> strings[key] = node.getString(key) }
            }
            root.optJSONObject(KeyLists)?.let { node ->
                node.keys().forEach { key ->
                    val array = node.getJSONArray(key)
                    lists[key] = List(array.length()) { index -> array.getString(index) }
                }
            }
        }.onFailure {
            strings.clear()
            lists.clear()
            MLog.e("dexkit cache load failed, starting empty", it)
        }
    }

    private companion object {
        const val KeyStamp = "stamp"
        const val KeyStrings = "strings"
        const val KeyLists = "lists"
    }
}
