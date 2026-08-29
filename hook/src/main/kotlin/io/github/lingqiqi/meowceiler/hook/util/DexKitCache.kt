// DexKitCacheBridge.Cache 标了 @DexKitExperimentalApi，同 DexKit.kt 的理由显式 opt-in。
@file:OptIn(DexKitExperimentalApi::class)

package io.github.lingqiqi.meowceiler.hook.util

import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.io.File

/**
 * DexKit 查询结果的磁盘缓存，写在宿主自己的 cache 目录下。
 *
 * DexKit 存进来的只是描述符字符串（`Lcom/x/Y;->z(I)V` 这种），命中缓存时不会创建原生桥 ——
 * 这是 DexKit 类功能启动开销的大头，也是这个类存在的唯一理由。
 *
 * [stamp] 变了就整份作废。宿主装了新版本，上一版扒出来的方法签名不能再信。
 */
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

    /** 会话结束时落盘。整个会话只写一次文件。 */
    @Synchronized
    fun flush() {
        if (!dirty) return
        val root = JSONObject()
        root.put(KeyStamp, stamp)
        root.put(KeyStrings, JSONObject(strings as Map<*, *>))
        root.put(KeyLists, JSONObject(lists.mapValues { JSONArray(it.value) } as Map<*, *>))

        runCatching {
            file.parentFile?.mkdirs()
            // 先写临时文件再 rename：宿主可能在任何时刻被杀，半截 JSON 会让下次启动整份作废。
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
            // 读坏了当没有，下次查询会重新写一份。
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
