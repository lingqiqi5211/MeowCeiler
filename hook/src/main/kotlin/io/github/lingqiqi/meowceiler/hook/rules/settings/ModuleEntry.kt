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
     * 各位置的锚点，插在命中那条之后。
     *
     * 用系统设置自己的 id 资源名找，不按下标写死 —— 条目顺序各版本会变。名字是在设备上把整份
     * header 列表 dump 出来核对过的：「更多设置」是 other_advanced_settings。别再把
     * app_timer 当锚点，那条是「应用使用时间」，先前就是插到那儿去了。
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
            // hookAfter 而不是裸的 hookManaged：异常不会漏进系统设置的调用栈（会让它崩），
            // 而且会记到安全模式上。所以这里不再自己 runCatching —— 自己吞掉的话就统计不到了。
            .hookAfter { param ->
                val activity = param.thisObjectOrNull as? Activity ?: return@hookAfter
                @Suppress("UNCHECKED_CAST")
                val headers = param.args.getOrNull(0) as? MutableList<Any> ?: return@hookAfter
                if (headers.any { idField.getLong(it) == HeaderId }) return@hookAfter

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
                // 跟着邻居走分组，否则这一条会被画到分组的圆角外面。
                headers.getOrNull((at - 1).coerceAtLeast(0))?.let { neighbour ->
                    groupIdField.setInt(header, groupIdField.getInt(neighbour))
                }
                headers.add(at, header)
                MLog.d("$id: inserted at $at")
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
     * 给自己那条补图标。
     *
     * 绑定点是反编译 `Settings.apk` 找到的 `HeaderAdapter.setIcon(HeaderViewHolder, Header)`：
     * ```
     * int i = header.iconRes;
     * if (i != 0) { icon.setVisibility(VISIBLE); icon.setImageResource(header.iconRes); }
     * else        { icon.setVisibility(INVISIBLE); }
     * ```
     * 所以挂 after：宿主看我们 iconRes 是 0 会把图标位设成 INVISIBLE，我们在它之后把 Drawable
     * 和可见性都补回去。
     *
     * **不要**去填 iconRes。它由**宿主的** Resources 解析，而模块 apk 的包 id 同样是 0x7f、
     * 和 com.android.settings 的资源在一个 id 空间：填模块的 id 会命中设置自己的某个资源，
     * 图标渲染成巨大一张图；再用 Resources.addLoaders 把模块资源表并进宿主想让 id 生效，
     * 同 id 空间的冲突会让设置别处的资源查找也出错 —— 这两条都真的把设置搞崩过。
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
            // setIcon 每次绑定行都会走。异常由 hookAfter 统一挡住并计入安全模式，
            // 这里不再自己 runCatching —— 自己吞掉就统计不到了。
            .hookAfter { param ->
                val header = param.args.getOrNull(1) ?: return@hookAfter
                if (idField.getLong(header) != HeaderId) return@hookAfter

                val holder = param.args.getOrNull(0) ?: return@hookAfter
                val iconView = holder.javaClass.getField("icon").get(holder) as? ImageView
                    ?: return@hookAfter

                iconView.setImageBitmap(iconBitmap(iconView) ?: return@hookAfter)
                iconView.visibility = View.VISIBLE
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

    /**
     * 按宿主的 header_icon_size 出图并缓存。
     *
     * 宿主对 BitmapDrawable 会自己缩放到这个尺寸，对矢量则不会 —— 直接给矢量的话它按自身
     * 固有尺寸走，和邻居不齐。所以这里自己渲成同尺寸的位图。
     */
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
