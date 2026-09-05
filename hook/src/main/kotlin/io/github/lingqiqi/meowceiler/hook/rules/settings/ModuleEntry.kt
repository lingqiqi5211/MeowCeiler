package io.github.lingqiqi.meowceiler.hook.rules.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import io.github.lingqiqi.meowceiler.hook.base.Feature
import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.hook.util.Settings
import io.github.lingqiqi.meowceiler.shared.ModuleName
import io.github.lingqiqi.meowceiler.shared.ModulePackage
import io.github.lingqiqi.meowceiler.shared.ModuleSettingsActivity
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.SettingsEntryPosition
import io.github.lingqiqi5211.ezhooktool.core.findField
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.toClass
import io.github.lingqiqi5211.ezhooktool.core.toClassOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import java.lang.reflect.Field

@Feature(
    name = "系统设置模块入口",
    since = "2026-08-17",
    target = "Settings 17.03.260226.r",
    updated = "2026-08-18",
)
object ModuleEntry : StaticHooker() {
    /** 自己插的那条的 id。updateHeaderList 会被反复调用，靠它判重。 */
    private const val HeaderId = 20260817L

    private const val HeaderClassName =
        "com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header"

    /** RecyclerView.Adapter，不是 ListAdapter 也不是 Preference —— 反编译设置 apk 确认的。 */
    private const val AdapterClassName = "com.android.settings.MiuiSettings\$HeaderAdapter"

    private const val IconName = "ic_settings_entry"

    /** 宿主自己给每个 header 图标用的尺寸，照抄才能和邻居对齐。 */
    private const val IconSizeDimen = "header_icon_size"

    /**
     * 各位置的锚点，按系统设置的 id 资源名找，条目顺序各版本会变。
     * 「更多设置」是 other_advanced_settings；app_timer 是「应用使用时间」，别当锚点。
     */
    private val Anchors = mapOf(
        SettingsEntryPosition.Device to "my_device",
        SettingsEntryPosition.Launcher to "launcher_settings",
        SettingsEntryPosition.More to "other_advanced_settings",
    )

    private val position
        get() = SettingsEntryPosition.from(Settings.read(Preferences.SettingsEntry.Position))

    /** 选「不显示」时整个 hook 不装，宿主零负担。 */
    override val extraCondition: Boolean get() = position != SettingsEntryPosition.Off

    override fun onHook() {
        val headerClass = HeaderClassName.toClassOrNull() ?: run {
            MLog.w("$id: $HeaderClassName not found, skipping")
            return
        }
        val idField = findField(headerClass) { name("id") }
        val titleField = findField(headerClass) { name("title") }
        val intentField = findField(headerClass) { name("intent") }
        val groupIdField = findField(headerClass) { name("groupId") }

        "com.android.settings.MiuiSettings".toClass()
            .findMethod {
                name("updateHeaderList")
                paramCount(1)
            }
            .createHook {
                after { param ->
                    val activity = param.thisObjectOrNull as? Activity ?: return@after
                    @Suppress("UNCHECKED_CAST")
                    val headers = param.args.getOrNull(0) as? MutableList<Any> ?: return@after
                    if (headers.any { idField.getLong(it) == HeaderId }) return@after

                    val header = headerClass.getDeclaredConstructor()
                        .apply { isAccessible = true }
                        .newInstance()
                    idField.setLong(header, HeaderId)
                    titleField.set(header, ModuleName)
                    intentField.set(
                        header,
                        Intent().setClassName(ModulePackage, ModuleSettingsActivity),
                    )

                    val at = insertPosition(activity, headers, idField)
                    headers.getOrNull((at - 1).coerceAtLeast(0))?.let { neighbour ->
                        groupIdField.setInt(header, groupIdField.getInt(neighbour))
                    }
                    headers.add(at, header)
                    MLog.d("$id: inserted at $at")
                }
            }

        hookIcon(idField)
    }

    /** 锚点不存在时退到列表尾部，宁可位置不对也别把入口整个丢掉。 */
    private fun insertPosition(
        activity: Activity,
        headers: List<Any>,
        idField: Field,
    ): Int {
        val anchor = Anchors[position] ?: return headers.size
        val anchorId = activity.resources
            .getIdentifier(anchor, "id", activity.packageName)
            .toLong()
        if (anchorId == 0L) return headers.size

        val index = headers.indexOfFirst { idField.getLong(it) == anchorId }
        return if (index >= 0) index + 1 else headers.size
    }

    /**
     * 给自己那条补图标。挂 after：宿主看 iconRes 为 0 会把图标位设成 INVISIBLE，之后再补 Drawable 和可见性。
     * 不填 iconRes：它由宿主 Resources 解析，模块 id 在宿主里对不上，填了会命中别的资源。真崩过。
     */
    private fun hookIcon(idField: Field) {
        val adapterClass = AdapterClassName.toClassOrNull() ?: run {
            MLog.w("$id: $AdapterClassName not found, entry stays iconless")
            return
        }
        adapterClass
            .findMethod {
                name("setIcon")
                paramCount(2)
            }
            .createHook {
                after { param ->
                    val header = param.args.getOrNull(1) ?: return@after
                    if (idField.getLong(header) != HeaderId) return@after

                    val holder = param.args.getOrNull(0) ?: return@after
                    val iconView = holder.javaClass.getField("icon").get(holder) as? ImageView
                        ?: return@after

                    iconView.setImageBitmap(iconBitmap(iconView) ?: return@after)
                    iconView.visibility = View.VISIBLE
                }
            }
    }

    private val icon: Drawable? by lazy {
        runCatching {
            val resources = EzXposed.moduleRes
            val resId = resources.getIdentifier(IconName, "drawable", ModulePackage)
            if (resId == 0) null else resources.getDrawable(resId, null)
        }.getOrNull()
    }

    private var cachedBitmap: Bitmap? = null

    /** 按宿主的 header_icon_size 渲成位图。宿主只缩放 BitmapDrawable，矢量按固有尺寸走，会和邻居不齐。 */
    private fun iconBitmap(iconView: ImageView): Bitmap? {
        cachedBitmap?.let { return it }
        val drawable = icon ?: return null
        val resources = iconView.resources
        val dimenId = resources.getIdentifier(
            IconSizeDimen,
            "dimen",
            iconView.context.packageName,
        )
        val size = if (dimenId != 0) {
            resources.getDimensionPixelSize(dimenId)
        } else {
            drawable.intrinsicWidth.coerceAtLeast(1)
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            cachedBitmap = bitmap
        }
    }
}
