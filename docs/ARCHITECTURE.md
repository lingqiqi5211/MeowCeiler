# MeowCeiler 架构设计

> 面向单一 HyperOS 大版本的 libxposed 模块。
> 参考实现:[XiaomiHelper](https://github.com/HowieHChen/XiaomiHelper)(Hooker 树 / scope 分发 / 共享 PreferenceKey)、
> [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)(崩溃隔离 / DexKit 阶段约定 / 性能取舍的实际尺度)。
>
> **本文的结构已经用一个端到端切片编译验证过**(`:shared` 一个 feature、`:hook` 一个
> `StaticHooker`、`:ui` 一个开关,`./gradlew :app:assembleDebug` 通过,`META-INF/xposed/*`
> 确认进包)，并且已在真机(REDMI K90 Ultra / OS3.0、Redmi Note 12 Turbo / OS4.0)上跑通模块加载
> 与 hook 装载。

---

## 1. 技术选型

| 项 | 选择 | 说明 |
|---|---|---|
| Hook 框架 | libxposed API **102** | MeowUI 只支持 libxposed;EzHookTool `hook-xposed-102` 带热重载 |
| Hook 工具 | `ezhooktool:core` + `:hook-xposed-102:1.1.3` | Maven,`libxposed:api` 必须 `compileOnly` |
| UI 库 | MeowUI `test` 分支(`0.1.5-rc01`) | **git submodule + `includeBuild`**,见 §11 |
| 语言 / 构建 | Kotlin **2.4.10** · AGP **9.3.1** · JDK 25 | 与 MeowUI / EzHookTool / HyperCeiler 对齐,见 §11 |

libxposed API 需要仓库 `https://api.xposed.info/`。

### 模块注册走 java-resources

libxposed 102 不读 Manifest。`app/src/main/resources/META-INF/xposed/` 三个文件:

```
module.prop        minApiVersion=102 / targetApiVersion=102 / autoHotReload=true
java_init.list     io.github.lingqiqi.meowceiler.hook.HookEntry
scope.list         默认建议的目标包名,每行一个
```

`app/build.gradle.kts` 需要 `packaging { resources { merges += "META-INF/xposed/*" } }`。

**不写 `staticScope`**,作用域交给用户在管理器里勾选,`scope.list` 只是默认建议。
代价是模块可能被启用到清单外的应用上 —— 所以 §6 入口处的包名判断是必需的闸,不是防御性代码。

---

## 2. 模块划分

**六个模块,按层分,不按宿主分。**

```
MeowCeiler/
├── MeowUI/                 git submodule(见 §11)
├── build-logic/            convention plugin
├── app/        :app        打包壳:Manifest、META-INF/xposed、Application、SettingsActivity
├── ui/         :ui         设置界面(MeowUI)
├── shared/     :shared     契约:Preferences / Scope / Version;唯一声明 MeowUI 的地方
├── hook/       :hook       全部 hook 代码
├── hidden-api/ :hidden-api 隐藏 API 桩(compileOnly,不进包)
├── service/    :service    【预留】native hook 服务客户端(§10)
├── native/     :native     【预留】进程内 JNI 桥(§10)
└── docs/
```

```
:app ─┬─> :ui    ──> :shared ──> meowui-xposed(api 了 meowui)
      └─> :hook  ──> :shared + EzHookTool + :service + :native
                 ──> :hidden-api  (compileOnlyApi)
```

### `:hidden-api`

框架里 `@hide` 的类没有公开 SDK 签名,直接调用编译不过。这个模块放它们的**桩声明**:
方法体只为编译通过,永远不会执行 —— 消费方一律 `compileOnlyApi`,桩不进 APK,
运行时用的是设备上真实的 framework 类。

做成 Android library 而不是纯 JVM 模块,是因为桩本身要引用 `Context` / `View` 这些平台类型,
需要 android.jar 在编译 classpath 上。HyperCeiler 的 `library/hidden-api` 是同样的处理。

**它能覆盖的是隐藏的「类」,不是公开类上隐藏的「成员」。**
`android.os.SystemProperties` 整体 @hide,桩不会和 android.jar 冲突,所以
`SafeMode` 可以直接 `SystemProperties.get(key, "")`,不用反射。
但 `PowerManager.goToSleep` 是公开类上的隐藏方法 —— android.jar 里已经有 `PowerManager`,
桩会撞车,那种情况仍然只能走反射(`DoubleTapToSleep` 里就是这么做的)。

**MeowUI 只在 `:shared` 里声明一次**,`:ui` 与 `:hook` 都通过它拿到 —— MeowUI 的两制品结构
本来就是为了「消费方只导一个依赖」,不去拆它(§4)。代价是 `:hook` 的编译期 classpath 上有
Compose;运行时开销为零,编译期由一条检查兜住,理由与做法见 §4 末尾。

### 为什么 hook 不再按宿主拆成多个 Gradle 模块

我上一版把 hook 拆成 `:hook:systemui` / `:hook:home` / `:hook:framework`,理由是「进程隔离」。
**那个理由是错的**:所有模块最终合进同一份 dex,类加载按需发生,`:hook:home` 的类不会在 SystemUI
进程里加载 —— 但这来自 §6 的 `when (packageName)` 分发,跟 Gradle 模块边界无关。

两个参考项目都是单 hook 模块 + 包级组织:XiaomiHelper 530 个文件、31 个宿主,全在一个 `app` 模块;
HyperCeiler 589 条规则全在一个 `libhook` 模块。按宿主拆 Gradle 模块只剩编译并行这一点收益,
而代价是十个 build 文件、跨宿主复用工具类要额外开模块。**不划算,不做。**

模块边界留在**层**上(UI / hook / 契约),这是真正需要强制的方向 —— `:hook` 编译期就看不到 `:ui`。
宿主的区分下沉到包结构,而 `hook/rules/systemui/statusbar/MiuiClock.kt` 这样的路径本身就足够好定位。

### `:hook` 内部包结构

```
hook/
├── HookEntry.kt          libxposed 入口 + 按包名分发
├── base/
│   ├── BaseHooker.kt     Hooker 树、启用状态、受管 hook handle(§3)
│   ├── ContextAwareHooker.kt  需要宿主 Context/资源的 hooker(§5)
│   └── ContextScope.kt   宿主资源访问(§5)
├── scopes/               一个宿主一个文件,内容只是一串 attach()
│   ├── SystemUi.kt
│   ├── MiuiHome.kt
│   ├── Framework.kt
│   └── SecurityCenter.kt
├── rules/                真正的功能实现,按宿主 → 区域分包
│   ├── systemui/statusbar/…  lockscreen/…  media/…
│   ├── miuihome/recent/…  gesture/…
│   └── framework/…
└── utils/
    ├── Settings.kt           持有 XposedModulePreferenceStore + 那个 scope(§4)
    ├── SafeMode.kt           功能级安全模式(§9)
    ├── HostApp.kt            宿主自己的 ApplicationInfo
    ├── DexKit.kt             DexKit 会话 + 结果落盘(§8)
    └── MLog.kt
```

---

## 3. 核心抽象:Hooker 树

这是整套设计的中心,直接借 XiaomiHelper 的 `BaseHooker`。它比「一个功能清单挨个
`install()`」多买到三样东西:

- **免重启开关**。`DynamicHooker` 能摘 hook,配合 §4 的 `observe` 实现开关一改立即生效。
- **组开关**。父 hooker 关掉,整棵子树自动失效,不用每个子功能重复判断。
- **受管 handle**。装出去的 hook 自动登记,unhook / 热重载清理不用手写。

**功能声明走构造参数,不用功能自己去读。** 这是相对 XiaomiHelper 的一处简化:它每个功能都要写一行
`updateSelfState(Preferences.X.Y.get())`,589 遍相同的样板。这里把 `:shared` 里那个
`Feature`(含开关与元信息,§7)声明成构造参数,框架统一读:`StaticHooker` 读一次、
`DynamicHooker` 自动订阅 —— 功能作者一行都不用写。

```kotlin
sealed class BaseHooker(
    /** 这个功能在 :shared 里的声明(含开关与元信息,见 §7)。null 表示无开关,恒随父级。 */
    private val feature: Feature? = null,
) {
    /** 日志标识。用与语言无关的 id，不用显示名 —— 宿主进程里没有模块资源（§7）。 */
    val id: String get() = feature?.id ?: this::class.java.simpleName

    private val handles = CopyOnWriteArraySet<HookHandle>()
    private val children = CopyOnWriteArraySet<BaseHooker>()

    private var selfEnabled = true
    private var parentEnabled = false
    private val effectiveEnabled get() = selfEnabled && parentEnabled

    /** 开关之外的启用条件。机型、宿主版本这类判断写这里,默认恒真。 */
    open val extraCondition: Boolean get() = true

    /** 只在需要 attach 子 hooker 或提前解析类时覆写。开关不用管,框架已经读好。 */
    open fun onInit() {}

    /** 仅在「自己开 && 父级开」时执行。真正装 hook 的地方。 */
    open fun onHook() {}

    /** 框架调用。先跑 onInit(),再统一应用开关 —— 所以子类永远不需要调 super。 */
    internal fun performInit() {
        runCatching { onInit() }                              // 单个功能崩溃不扩散
            .onFailure { MLog.e("init $id failed", it) }
        applySwitch()
    }

    private fun applySwitch() = when {
        feature == null -> updateSelfState(extraCondition)
        // Dynamic 订阅 Flow,开关一变就 hook / unhook;Static 同步读一次,不碰协程。
        this is DynamicHooker -> Settings.scope.launch {
            Settings.observe(feature.key).collect { updateSelfState(it && extraCondition) }
        }
        else -> updateSelfState(Settings.read(feature.key) && extraCondition)
    }

    @Synchronized
    fun updateSelfState(enabled: Boolean) {
        val old = effectiveEnabled
        selfEnabled = enabled
        applyStateChange(old)
    }

    private fun applyStateChange(old: Boolean) {
        val new = effectiveEnabled
        if (old == new) return
        if (new) {
            if (handles.isEmpty()) onHook()
        } else when (this) {
            // 静态 hooker 装了就不摘,关掉需要重启宿主
            is StaticHooker -> MLog.d("$id: static hooker, unhook skipped")
            is DynamicHooker -> { handles.forEach { it.unhook() }; handles.clear() }
        }
        children.forEach { it.updateParentState(new) }
    }

    /** 挂子 hooker。子级继承父级的启用状态,父关则子全关。 */
    fun attach(child: BaseHooker) {
        if (!children.add(child)) return
        child.performInit()
        child.updateParentState(effectiveEnabled)
    }

    /** 走这个装 hook,handle 才会被登记。 */
    protected fun Method.hookManaged(block: HookFactory.() -> Unit) {
        handles += createHook { block() }
    }
}

/** 装了不摘。绝大多数功能用这个 —— 关掉后重启宿主生效。 */
abstract class StaticHooker(feature: Feature? = null) : BaseHooker(feature)

/** 可运行时摘除。用于确实需要免重启切换的功能。 */
abstract class DynamicHooker(feature: Feature? = null) : BaseHooker(feature)
```

两点值得注意:

- **`performInit()` 而不是让子类调 `super.onInit()`。** 读开关的时机由框架控制,
  子类忘记调 super 这个经典坑从设计上就不存在。
- **`extraCondition` 是唯一的扩展点。** 机型判断这类条件写在这里,和开关自动 AND 起来。
  更简单的情况直接把默认值做成设备相关的即可 —— `PreferenceKey("split_screen", Device.isPad)`,
  XiaomiHelper 就这么用。

整体还比 XiaomiHelper 的版本短一截,因为 `module` / `classLoader` / `packageName` 这些
EzHookTool 的 `EzXposed` 已经全局持有了,不用逐层往下传。

### 四种典型写法

功能文件顶部按宿主 import 一次分组,引用就短了:

```kotlin
import io.github.lingqiqi.meowceiler.shared.Preferences.SystemUi
```

**普通功能** —— 只有 `onHook()` 一个成员。元信息在 `:shared` 的 `feature(...)` 里(§7),
开关由框架读,`onInit()` 不用覆写:

```kotlin
object LockscreenDoubleTapToSleep : StaticHooker(SystemUi.DoubleTapToSleep) {
    override fun onHook() {
        // 拦 dispatchTouchEvent,250ms 内的第二次原位点击直接息屏
        "com.android.systemui.shade.NotificationsQuickSettingsContainer".toClass()
            .findMethod { name("dispatchTouchEvent") }
            .hookManaged { … }
    }
}
```

**免重启开关** —— 只是把基类换成 `DynamicHooker`,`observe` 由框架接上:

```kotlin
object HideCarrierLabel : DynamicHooker(SystemUi.HideCarrierLabel) {
    override fun onHook() { … }
}
```

**功能组** —— 总开关仍是构造参数,`onInit()` 只写 attach:

```kotlin
object MediaControl : StaticHooker(SystemUi.MediaControl) {
    override fun onInit() {
        attach(CustomBackground)   // 总开关一关,这三个全部失效
        attach(CustomLayout)
        attach(CustomProgressBar)
    }
}
```

**无开关的基础 hooker** —— 不传构造参数,恒随父级:

```kotlin
object SystemUiResources : ContextAwareHooker() {   // 无 feature → 父级开它就开
    override val targetPackage = Scope.SystemUi
    …
}
```

### scope 文件

每个宿主一个,内容就是一串 `attach()`,没有别的逻辑:

```kotlin
object SystemUi : StaticHooker() {          // scope 自身不带开关,由 HookEntry 直接启用
    override fun onInit() {
        attach(SystemUiResources)          // 先解析资源 ID,见 §5
        attach(LockscreenDoubleTapToSleep)
        attach(HideCarrierLabel)
        attach(MediaControl)               // 自带子树
        attach(StatusBarClock)
    }
}
```

### `StaticHooker` 还是 `DynamicHooker`

默认 `StaticHooker`。只有当「改完立刻看到效果」对这个功能确实重要时才用 `DynamicHooker` ——
它要求 `onHook()` 里装的东西都能干净摘除(纯方法 hook 可以;注册了监听器、替换了 View、
改了静态字段的就不行,摘了也回不去)。判断标准就一句:**unhook 之后宿主能回到原状吗?**

---

## 4. 双进程配置通道

libxposed 的 remote preferences 是唯一通道,两侧共用**同一个 `PreferenceKey` 对象**,
并且两侧都用 MeowUI 自己的 store —— 不自己写一套。

```
设置进程                              共享存储                    宿主进程
setMeowXposedContent(NAME)  ──写──▶  "meowceiler"  ──读──▶  createPreferenceStore(NAME)
XposedServicePreferenceStore        libxposed remote prefs     XposedModulePreferenceStore
```

### MeowUI 只导一个依赖

MeowUI 发两个制品,设计意图是消费方只导一个:普通 Compose 应用用 `meowui`,
Xposed 模块用 `meowui-xposed`(它 `api` 了 `meowui`,并且 `libxposed` 包本身不含 Compose)。

MeowCeiler 因此**只在 `:shared` 里声明这一次**,`:ui` 与 `:hook` 都通过 `:shared` 拿到:

```kotlin
// shared/build.gradle.kts
dependencies {
    api("io.github.lingqiqi5211.meowui:meowui-xposed:<version>")
}
```

版本只出现在一处。`:shared` 因此是 Android library(`meowui-xposed` 是 AAR),不是纯 JVM 模块。

### `:shared` 的契约

直接用 MeowUI 的 `PreferenceKey`,不再自己定义一层描述符:

```kotlin
package io.github.lingqiqi.meowceiler.shared

object Preferences {
    const val NAME = "meowceiler"
    const val VERSION = 1

    /** 模块自身的设置,不是功能开关,所以是裸 PreferenceKey,没有元信息。 */
    object Module {
        val Enabled = PreferenceKey("module_enabled", true)
        val Debug   = PreferenceKey("module_debug", false)
    }

    /** 功能开关一律用 feature(),四个字段必填 —— 见 §7。 */
    object SystemUi {
        val DoubleTapToSleep = feature(
            id      = "systemui_lockscreen_double_tap",
            name    = "锁屏双击息屏",
            since   = "2026-08-11",
            target  = Version.SystemUi,
            updated = "2026-08-14",
        )
        val HideCarrierLabel = feature(
            id      = "systemui_statusbar_hide_carrier",
            name    = "隐藏运营商名称",
            since   = "2026-08-11",
            target  = Version.SystemUi,
            updated = "2026-08-11",
        )
    }
}

/** 目标包名。同时是 scope.list 的事实来源。 */
object Scope {
    const val Framework      = "android"
    const val SystemUi       = "com.android.systemui"
    const val MiuiHome       = "com.miui.home"
    const val SecurityCenter = "com.miui.securitycenter"
}

/** 已验证适配的宿主版本。feature(...) 的 target 引用这里,不抄字符串。 */
object Version {
    const val HyperOs        = "3.0"
    const val SystemUi       = "OS3.0.x"
    const val MiuiHome       = "RELEASE-5.x"
    const val SecurityCenter = "12.1.5"
}
```

`Scope` / `Version` 两个 object 直接搬 XiaomiHelper 的做法。`Version` 尤其值得 —— 宿主版本
记在一处,升级时改一行,而不是去 grep 几十个功能文件里的版本注释。

共用同一个 `PreferenceKey` 对象换来的是「key 名、类型、默认值只有一处定义」。
早先我自己定义 `SwitchSpec` 再两侧各写适配器,两份定义就有两份默认值,反而是那类 bug 的来源。

### hook 侧直接用 MeowUI 的 store,不自己写

`meowui-xposed` 已经提供了 hook 进程的 store,MeowUI 的 `AGENTS.md` 也明确规定
「Hook 进程使用 `XposedModulePreferenceStore` 或 `XposedModule.createPreferenceStore`」。
早先我在这里手写了一个 `RemotePreferences`(约 60 行:自己包 `getRemotePreferences`、
自己按 `key.type` 分派、自己维护 listener 路由表),那是重复实现,已删除。

剩下的只是一层极薄的持有者,把 store 和那个 scope 收在一处:

```kotlin
/** hook 进程里的设置入口。每进程一个,onModuleLoaded 时建好。 */
object Settings {
    private var store: XposedModulePreferenceStore? = null

    /**
     * 不要写成 `by lazy` —— lazy 只初始化一次，[close] 取消后拿到的还是那个已取消的 scope，
     * launch 立即返回，DynamicHooker 在热重载之后会静默失效。
     */
    var scope: CoroutineScope = newScope()
        private set

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(module: XposedModule) {
        store = module.createPreferenceStore(Preferences.NAME)
        if (!scope.isActive) scope = newScope()
    }

    /** remote 不可用时回落 key 的默认值。 */
    fun <T : Any> read(key: PreferenceKey<T>): T = requireStore().read(key)

    fun <T : Any> observe(key: PreferenceKey<T>): Flow<T> = requireStore().observe(key)

    fun close() {
        store?.close()
        store = null
        scope.cancel()
    }

    private fun requireStore(): XposedModulePreferenceStore =
        checkNotNull(store) { "Settings.init() has not been called in this process" }
}
```

`XposedModulePreferenceStore` 是 `PreferenceStore` + `AutoCloseable`,给出两个能力:

| 用法 | 语义 | 代价 |
|---|---|---|
| `Settings.read(key)` | 同步类型化读取,失败回落 `key.defaultValue` | 无协程,一次 SharedPreferences 读 |
| `Settings.observe(key)` | `Flow<T>`,先发当前值,之后每次变更发一次,自带去重 | 需要一个 CoroutineScope |

已核对过实现:`observe()` 底层是真的 `OnSharedPreferenceChangeListener` 喂进 `SharedFlow`,
按 key 名过滤后映射成类型化读取(`CorePreferenceStoreAdapter`)。`FixedRemotePreferencesConnection`
里的「Fixed」指的是**连接**固定(hook 进程直接从 `XposedModule.getRemotePreferences` 拿,
不需要等 service 绑定),不是值固定 —— 所以设置进程写入能实时到达。

这正好和 §3 的 Static / Dynamic 分层对上:

- `StaticHooker` → `read()`,**完全不碰协程**。
- `DynamicHooker` → `observe()`,需要 scope。整个 hook 进程共用一个
  `CoroutineScope(SupervisorJob() + Dispatchers.Default)`(建对象很便宜,真正起线程要等第一次
  dispatch,所以直接在 `init()` 里建,不用 lazy),
  每个 DynamicHooker 一个 collector。协程开销由选择了免重启的那几个功能承担,其余功能不付。

> MeowUI 的 `AGENTS.md` 有一条「Hook callback 不重复创建长期 Flow collector」——
> 上面这个「一个 scope、每个 DynamicHooker 一个 collector」正是为了满足它。

remote 不可用时 `read()` 回落 `key.defaultValue`,所以**默认值必须是对宿主安全的那个值**。
热重载清理时要 `Settings.close()`,见 §6。

### 「hook 侧不靠 UI」现在靠什么保证

`meowui-xposed` 是一个 AAR,`api` 了 `meowui`,所以 `:hook` 的**编译期 classpath 上确实有 Compose**。
这是两制品结构的必然代价,换来的是「只导一个依赖」。诚实地说清各自的边界:

- **运行时开销为零。** Compose 的类一次都不会被加载 —— 类加载是惰性的,而 hook 代码从不引用
  `component` / `theme` / `xposed` 这三个包。宿主进程只会加载
  `PreferenceKey` / `PreferenceType` / `libxposed` 包那十几个类。
- **编译期靠一条检查兜住,而不是靠自觉。** `build-logic` 里加一个校验任务:
  扫 `hook/` 下的源码,出现 `androidx.compose` 或 MeowUI 的
  `component` / `theme` / `xposed` 包的 import 就构建失败。十几行,和模块边界的效果接近。

```kotlin
// build-logic:约十行,挂到 :hook 的 check 上
val forbidden = listOf("androidx.compose", ".meowui.component", ".meowui.theme", ".meowui.xposed")
```

---

## 5. 宿主 Context 与资源

大量 UI 类 hook 需要宿主的 `Resources` 才能拿到 id / dimen / string,而
`onPackageLoaded` 时 Application 还没建好。

**不用自己去 hook `Application.attachBaseContext`** —— EzHookTool 已经提供了
`EzXposed.runOnApplicationAttach`（搭切片时才发现），基类因此比原设计短一半:

```kotlin
abstract class ContextAwareHooker(feature: Feature? = null) : StaticHooker(feature) {

    abstract val targetPackage: String

    private var ready = false

    final override fun onHook() {
        EzXposed.runOnApplicationAttach { context ->
            if (ready) return@runOnApplicationAttach
            ready = true
            ContextScope(context, targetPackage).onReady()
        }
    }

    abstract fun ContextScope.onReady()
}

/** `getIdentifier` 是字符串查表，按约定在 onReady 里一次性解析完，回调里只用 Int。 */
class ContextScope(val context: Context, private val packageName: String) {

    val res: Resources get() = context.resources

    fun String.toId(): Int = res.getIdentifier(this, "id", packageName)

    fun String.toDrawableId(): Int = res.getIdentifier(this, "drawable", packageName)

    fun String.toStringId(): Int = res.getIdentifier(this, "string", packageName)

    fun String.toDimenId(): Int = res.getIdentifier(this, "dimen", packageName)

    fun String.toLayoutId(): Int = res.getIdentifier(this, "layout", packageName)

    fun Int.dp2px(): Int = (this * res.displayMetrics.density + 0.5f).toInt()
}
```

配套约定:每个宿主有一个 `<Host>Resources` hooker,在 scope 里**第一个** attach,
把该宿主所有要用的资源 id 解析一次并暴露出去,其他功能直接引用:

```kotlin
object MiuiHomeResources : ContextAwareHooker() {
    override val targetPackage = Scope.MiuiHome

    var statusBarRecentMemoryInfo = 0; private set
    var icTaskSmallWindow = 0; private set

    override fun ContextScope.onReady() {
        statusBarRecentMemoryInfo = "status_bar_recent_memory_info1".toStringId()
        icTaskSmallWindow = "ic_task_small_window".toDrawableId()
    }
}
```

`getIdentifier` 是字符串查表,不便宜。集中解析一次,回调里用 `Int` —— 这条正好落在
§8 唯一的那条硬规则上。

---

## 6. 入口与路由

```kotlin
class HookEntry : XposedModule() {

    private var moduleEnabled = true

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        Settings.init(this)

        moduleEnabled = Settings.read(Preferences.Module.Enabled)
        MLog.debugEnabled = Settings.read(Preferences.Module.Debug)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        if (rootHookerFor(param.packageName) == null) return    // 未知宿主静默退出

        EzXposed.initOnPackageLoaded(param)
        install(param.packageName)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // 旧 generation 退场时清理,不能等到 onHotReloaded —— 那时已经是新实例了。
        Settings.close()
        return EzXposed.handleHotReloading(param)
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        // targetReady 后面还有个带默认值的 onExtra,所以不能用尾随 lambda。
        EzXposed.handleHotReloadedWithTargetReady(
            base = this,
            param = param,
            targetReady = {
                Settings.init(this@HookEntry)
                install(EzXposed.packageName)
            },
        )
    }

    /** onPackageLoaded 与热重载共用的装配路径。 */
    private fun install(packageName: String) {
        if (!moduleEnabled) return

        val root = rootHookerFor(packageName) ?: return
        root.performInit()
        root.updateParentState(true)
    }

    /** 未命中分支的类不会被加载 —— 运行时的进程间隔离全部来自这里。 */
    private fun rootHookerFor(packageName: String): BaseHooker? = when (packageName) {
        Scope.SystemUi -> SystemUi
        else -> null
    }
}
```

**装 hook 必须等 `EzXposed.onTargetReady`,不能在 `onPackageLoaded` 里做。**
`onPackageLoaded` 是从 `LoadedApk.createOrUpdateClassLoaderLocked` 调来的,那时宿主自己的 dex
还没进 ClassLoader(`DexPathList` 是空的),`toClass()` 一律 `ClassNotFound`。
真机上就是栽在这里 —— 崩溃隔离把它兜住了,所以只在日志里留下一条 error,不影响宿主。

调用链是 `HookEntry → scope → (可选的组 hooker) → 功能.onHook() → EzHookTool`,四到五层封顶,
没有 Manager / Controller / Handler 这类转发层。

**未知宿主必须静默退出。**因为不启用 `staticScope`(§1),用户可以把模块勾到任意应用上。
`rootHookerFor` 返回 null 就直接 return —— 被误勾的应用只承担一次 `when` 字符串比较,
不加载任何 rule 类、不装任何 hook。想更彻底可以在 `else` 分支调 `EzXposed.detachCurrentEntry()`。

---

## 7. 功能元信息与注释公约

「设置页要展示适配版本」这个要求把元信息的位置**强制**定下来了:它必须在 `:shared`。

### 为什么不能留在 `:hook`

- `:ui → :hook` 会倒转分层,不能走。
- `:app` 同时依赖两边,看起来能在 `:app` 里枚举 hooker 的元信息 —— **但这条是陷阱**。
  `BaseHooker` 的签名里有 `HookHandle`、`Settings` 里有 `XposedModule`,而 `libxposed:api`
  是 `compileOnly`,在模块自己的 App 进程里根本不存在。设置进程一加载这些类,
  校验阶段就 `NoClassDefFoundError`。

所以元信息只能放在一个不引用 libxposed 的地方,那就是 `:shared`。既然要放过去,
**干脆和 key 绑在一起**,顺带解决了「功能名称写两遍」的问题(以前 `FeatureMeta.name`
和 UI 的 `title` 是两份字符串)。

### `:shared` 里的 `Feature`

```kotlin
class Feature(
    val key: PreferenceKey<Boolean>,
    /** 功能名称 */            val name: String,
    /** 引入时间 yyyy-MM-dd */  val since: String,
    /** 适配版本,引用 Version */ val target: String,
    /** 最近更新 yyyy-MM-dd */  val updated: String,
)

/** 四个字段都是必填的命名参数,漏一个编译不过。 */
fun feature(
    id: String,
    default: Boolean = false,
    name: String,
    since: String,
    target: String,
    updated: String,
) = Feature(PreferenceKey(id, default), name, since, target, updated)
```

一个功能的全部声明就一处:

```kotlin
object Preferences {
    const val NAME = "meowceiler"

    object SystemUi {
        val DoubleTapToSleep = feature(
            id      = "systemui_lockscreen_double_tap",
            name    = "锁屏双击息屏",
            since   = "2026-08-11",
            target  = Version.SystemUi,
            updated = "2026-08-14",
        )
    }
}
```

Hook 侧因此**不再需要 `meta` 覆写**,`BaseHooker` 从 `Feature` 直接取:

```kotlin
object LockscreenDoubleTapToSleep : StaticHooker(SystemUi.DoubleTapToSleep) {
    override fun onHook() { … }        // 只剩这一个成员
}
```

UI 侧包一层,保证所有功能项展示一致,连标题都不用再写:

```kotlin
fun MeowPreferenceSectionScope.MeowFeaturePreference(feature: Feature) =
    item(key = feature.id, container = false) {
        // 必须用别名指向顶层 Composable，见下面那条坑。
        SwitchPreferenceRow(
            key = feature.key,
            title = stringResource(feature.nameRes),
            summary = stringResource(R.string.feature_summary, feature.target, feature.updated),
        )
    }
```

> **真机才暴露的坑。** `item {}` 的 content lambda 仍在 `MeowPreferenceSectionScope`
> 的接收者作用域里,直接写 `MeowSwitchPreference(...)` 会解析到 **scope 成员**而不是顶层
> Composable。成员再调 `item()` 时 `collectingInBody` 已是 false,条目一个都登记不进去,
> 只会不停 `collectionEpoch++` —— 整行静默消失,不报错、不崩溃。
> MeowUI 自己的成员实现全都写全限定名就是为了避开它;我们用 import 别名达到同样效果。

`target` 填 `Version.SystemUi` 这样的**引用**而不是字符串字面量 —— 宿主升级时改 `:shared`
里的一行,所有功能跟着变。只有某个功能确实卡在旧版本时才写死字符串,那种情况本身就该显眼。

### 与你最初要求的一处偏离

你原话是「每个功能 hook 头(函数)需要写」这四个字段。现在它们在 `:shared` 而不是 hook 文件里 ——
这是「设置页要展示」这条要求逼出来的,上面那两个原因都绕不过。

缓解:hooker 的声明行 `StaticHooker(SystemUi.DoubleTapToSleep)` 就点名了是哪个功能,
跳过去一步就到。反过来还多一个好处:每个宿主的功能清单连日期带在一个文件里,
一眼能看完整个盘子,比散在几十个文件里更好审。

> **待定:多语言。** `name` 现在是硬编码 `String`,只有中文时够用。若要 i18n,
> 得改成 `@StringRes` 的 Int,而资源在 `:ui` / `:app` —— `:shared` 引用它们的 `R` 会倒转依赖。
> 那时的解法是 `name` 只留给日志,UI 标题按 `feature.key.name` 去查字符串资源。
> 现在不做,免得为一个还不存在的需求先付复杂度。

两个参考项目都没有元信息约定(HyperCeiler 589 个文件里只有 17 个有 `@author`,是署名不是元数据),
这是本项目按你要求加的唯一一处额外仪式。就这四个字段,不扩展。

### 注释公约

- 功能声明:`:shared` 的 `feature(...)` 四个字段,必填。
- 功能内部:只写**为什么这么 hook** —— 目标类/方法为何是这个、有什么坑。一两行足够。
- 不写流水账,不写「这里遍历列表」这种复述代码的注释。
- **例外:bug 修复与性能修复要留注释**,写清症状与根因。否则后人会把缓存当冗余删掉。

---

## 8. 性能:框架兜底,功能代码保持普通

原则:**该被工程化的是框架层,功能代码就该是四五十行的直白实现。**

HyperCeiler 是现成的尺度。它框架层很重 —— DexKit 并行初始化、逐 hook 崩溃隔离、崩溃计数进安全模式、
热重载清理协议。但 589 条规则全是平铺直叙的代码:回调里按名反射、开关随手读、`runCatching {}`
兜住可能不存在的类。真正做了优化的只有 `StatusBarClockNew` 一个文件 —— 因为它确实撞到了掉帧。
XiaomiHelper 同样如此。这个比例是对的,照抄。

### 框架层保证(写一次,所有功能受益)

1. 未知宿主静默退出(§6),作用域外的进程一个 rule 类都不加载。
2. 安全模式:同一宿主连续出错到阈值就整个停掉(§9)。
3. 开关由框架在 `performInit()` 里统一读,`onHook()` 只在启用时执行 —— 关掉的功能连 hook 都不装。
4. `attach()` 内层 `runCatching`,单个功能 init 失败不影响兄弟功能与宿主。
5. hook handle 受管(§3),unhook 与热重载清理不依赖各功能自觉。
6. EzHookTool 的 Safe Mode（`EzXposed.safeMode`）**默认就是 true**，回调抛异常时回落原始逻辑，不用额外开。

### 功能代码的唯一硬规则

7. **昂贵的查找放在 `onInit()` / `onHook()` / `by lazy` 里,不要在 hook 回调里首次触发。**
   指 `findClass`、`findMethod`、DexKit、`Resources.getIdentifier` 这类要扫 dex 或查表的操作。

这是 HyperCeiler 唯一写进代码强制执行的性能约定(`initDexKit()` 阶段外调用直接抛
`IllegalStateException`),也是唯一一类真会造成可感知卡顿的错误。其余都能事后补,这条不行。

### 其余按需,不预先优化

- **回调里按名反射是常态**,`getObjectField("mXxx")` 不用避。两个参考项目都到处这么写。
  只有确实落在每帧 / 每次 measure 的路径上,才值得缓存 `Method`。
- **回调里读开关也可以**。remote preferences 首次加载后就是内存 map,一次读取是 map 查找量级。
- **日志正常写**:`MLog.d(TAG, "msg $x")` 就行,不必设计 inline lambda API 去省一次字符串拼接。
- **遇到卡顿再优化**,并按 §7 的例外留注释。

---

## 9. 为什么没有版本守卫

曾经有一个 `HyperOsGuard`:读 `ro.mi.os.version.code`,大版本对不上就一个 hook 都不装。
**已经去掉了。**

理由是它挡错了地方。真实情况是同一份代码要同时面对手上两台不同大版本的机器(OS3.0 与 OS4.0),
而绝大多数功能的目标类与字段在两代之间是一样的 —— QSGrid 用到的 `MiuiTileLayout.mColumns` /
`mMaxAllowedRows` 就在两台上逐字一致。用大版本一刀切,等于为了少数确实变了的功能把全部功能关掉。

承担这个职责的是另外三层,而且粒度都比大版本细:

- **每个功能自己的 `@Feature(target = ...)`** —— 写到宿主 App 版本这一级,是给人排查用的事实记录。
  真正的保护来自功能代码本身:`toClass()` / `findMethod` 找不到就抛,被 `attach()` 的 `runCatching`
  接住,只失败这一个功能。
- **安全模式(§6 的 `SafeMode`)** —— 同一宿主里连续出错到阈值就把它整个停掉,而且解锁前也拦得住。
  这比版本号判断准:它看的是「实际有没有崩」,不是「版本号像不像」。
- **未知宿主静默退出(§6)** —— 作用域之外的进程一个 rule 类都不加载。

代价是明确的:适配一个新大版本时不再有「全局关掉」这个开关,得逐个功能验。这正是 `@Feature`
的 `target` 字段和功能健康页存在的意义。
---

## 10. 为 native hook 预留的两个模块

「服务端」指的是**类似 LSPosed 自身的本地特权服务**,用来加载 so 层级的 hook,不是云端后端。
本项目是它的客户端。这里没有网络,只有本地 IPC。整件事分成进程内和跨进程两面,各占一个模块:

| 模块 | 位置 | 职责 |
|---|---|---|
| `:native` | 进程内 | JNI 桥:`System.loadLibrary` + `external fun` 声明,给 Rust agent 提供 Kotlin 侧入口 |
| `:service` | 跨进程 | 控制面客户端:发现并连接服务、能力/版本协商、请求加载指定 so hook、读取状态 |

### 依赖方向

`:hook` **可以**依赖 `:service` —— hook 进程需要请求加载 native hook。
(上一版我写成「`:remote` 绝不被 hook 模块依赖,hook 进程永不联网」,那是把它当成云端配置服务的
误解;本地 IPC 不是联网,这条约束不成立。)

`:ui` 也依赖 `:service`,用来在设置页展示服务是否可用、版本是否匹配。

### 真正的约束在连接方式上

hook 进程跑在 SystemUI、system_server 里面,所以控制面客户端有三条硬要求:

1. **惰性 + 有超时。** 绝不在宿主主线程上做同步 IPC 等待。一次卡住就是系统卡死。
2. **每进程只握手一次**,结果缓存。不要每个功能各连一次服务。
3. **服务缺失必须静默降级。** `NativeHookService.isAvailable == false` 时,依赖它的功能整体不启用,
   纯 Java 层的功能照常工作。绝不能因为服务没装就让宿主起不来。

### 和 Hooker 树的接法

第 3 条正好落在 §3 的 `extraCondition` 上,一行就够,不需要新的机制:

```kotlin
/** 需要 native hook 服务的功能。服务不可用时自动不启用。 */
abstract class NativeHooker(
    feature: Feature? = null,
) : StaticHooker(feature) {         // 继承 Static:native inline hook 一般摘不干净
    override val extraCondition: Boolean get() = NativeHookService.isAvailable
}
```

继承 `StaticHooker` 而不是直接继承 `BaseHooker`,是为了不破坏那个 sealed 层级 ——
`applyStateChange` 里的 `when (this)` 不用加分支。

### 现阶段只放这些

```kotlin
// :native
object NativeBridge {
    val isLoaded: Boolean by lazy {
        runCatching { System.loadLibrary("meowceiler") }.isSuccess
    }
}

// :service
interface NativeHookService {
    val isAvailable: Boolean
    /** 能力协商结果,含服务版本与支持的 hook 种类。 */
    suspend fun handshake(): Result<ServiceCapability>
}
```

**先不引入 Rust 工具链配置**(cargo-ndk 之类),避免现在就拖慢日常构建。Rust 源码后续放
`native/rust/`。等 Rust 侧的服务协议定下来再填 `:service` 的实现 —— 现在只需要目录、接口,
以及上面那三条约束写进文档,免得将来实现时踩。

---

## 11. 构建配置要点

### MeowUI 用 git submodule,不用 `../MeowUI`

XiaomiHelper 就是这么接 `hyperx-compose` 的。比相对路径强在:提交里锁定 commit,
构建可复现,也不要求两个仓库必须同级摆放。

```bash
git submodule add -b test https://github.com/lingqiqi5211/MeowUI.git third_party/meowui
git submodule update --init --recursive     # MeowUI 自己还嵌着 miuix 的 submodule
```

```kotlin
// settings.gradle.kts
includeBuild("third_party/meowui")          // 仓库内相对路径
```

钉 `test` 分支(当前 `0.1.5-rc01`),它跟进 miuix 主线上还没进正式版的组件。
注意 **`--recursive` 是必须的** ——
MeowUI 的 `test` 分支把 miuix 也做成了 submodule(`third_party/miuix`),它的
`settings.gradle.kts` 里有个 `require()` 检查,没拉全会直接报错退出。

用 `includeBuild`(复合构建)而不是 XiaomiHelper 那种 `include(":hyperx-compose")` ——
因为 MeowUI 有自己的版本目录、`vanniktech maven-publish` 配置和嵌套复合构建,
复合构建能让它继续用自己那套,依赖替换由 Gradle 按坐标自动完成。
`hyperx-compose` 是裸源码单模块,所以那边 `include` 就够。

### `:shared` 是 Android library,MeowUI 只在这里声明

`meowui-xposed` 是 AAR,所以 `:shared` 得是 Android library。用 `api` 声明,让
`:ui` 与 `:hook` 通过它拿到 —— MeowUI 的版本号只出现在这一处。

MeowUI 的 minSdk 是 26,`miuix-blur-android` 声明 minSdk 33,所以**应用 minSdk 低于 33 时**
主 Manifest 需要 `<uses-sdk tools:overrideLibrary="top.yukonga.miuix.kmp.blur" />`。
MeowCeiler 的 minSdk 是 **36**,**不需要这条**,Blur 也一定可用。

### 复合构建要对齐的不止 AGP 与 Kotlin

搭切片时踩到的两条,都会让构建在跟症状无关的地方炸:

**Gradle wrapper 版本也要一致。** MeowCeiler 原来是 9.3.1、MeowUI 是 9.6.1,结果外层 Gradle
去跑内层脚本时抛 `NoSuchMethodError: Settings_gradle.<init>(…PluginDependenciesSpec…)` ——
报错完全指不到版本不一致上。现已统一到 **9.6.1**。

**MeowCeiler 必须是 git 仓库,而且至少有一次 commit。** MeowUI 的 `test` 分支把 miuix 作为
submodule 一起构建,miuix 的 `module.publication` 插件用 `providers.exec` 跑
`git rev-list --count HEAD` 取版本号 —— 而 `providers.exec` 用的是**外层构建的工作目录**。
仓库没初始化(或没有 commit)时它 exit 128,报错停在 `miuix-blur/build.gradle.kts`,
一样看不出真正原因。

### 其他

- 仓库列表加 `maven("https://api.xposed.info/")`。
- `compileSdk = 37` + `compileSdkMinor = 0`,与 MeowUI 一致(SDK 目录是 `android-37.0`)。
- settings 里**不要**加 `plugins { id("…foojay-resolver-convention") }` —— 会解析失败,
  而且我们直接指定 `jvmTarget`,本来也不需要 toolchain 自动下载。
- `build-logic` 提供 `meowceiler.android.library` convention plugin。六个模块量不大,
  但 `compileSdk` / JDK / Kotlin 选项写六遍仍是纯噪音。
- `:app` 移除现在对 miuix 的直接依赖 —— MeowUI 内部已封装 miuix 与 material3,重复声明会版本冲突。
- R8:参考 EzHookTool 的 `consumer-rules.pro`,额外 keep `HookEntry` 及被反射引用的类。
- 考虑加 `stableIds.txt` 锁资源 ID(XiaomiHelper 有)—— 模块自带资源要注入宿主时,
  ID 漂移会导致热重载后引用错乱。

---

## 12. 新增一个功能:4 步

1. `:shared` 的 `Preferences` 里加一个 `feature(...)` —— 名称、引入时间、适配版本、
   最近更新都在这一处填(§7)。
2. `hook/rules/<宿主>/<区域>/` 加 `object XxxFeature : StaticHooker(那个 feature)`,
   写 `onHook()`。开关和元信息都不用管。
3. `hook/scopes/<宿主>.kt` 里加一行 `attach(XxxFeature)`。
4. `:ui` 页面加一行 `MeowFeaturePreference(那个 feature)` —— 标题和适配版本都从 feature 来,
   不用再写一遍。

位置固定。协作时冲突面集中在第 3、4 步的两个列表上。

---

## 13. 待拍板的参数

| 参数 | 现状 | 建议 |
|---|---|---|
| `minSdk` | **36 已定**(已改 `app/build.gradle.kts`) | Android 16。同时也把平台下限钉死了 —— 见下面一条 |
| 目标 HyperOS 大版本 | **不设限，已去掉守卫** | 手上两台分别是 OS3.0 与 OS4.0，绝大多数目标类两代一致。改为逐功能验(§9) |
| `scope.list` 默认包 | **先不整** | 实际写功能时再往里加。`Scope` 常量随功能增长,`scope.list` 跟着它走 |
| AGP / Kotlin | **9.3.1 / 2.4.10 已定**(已改 `libs.versions.toml`) | 脱离 alpha,并与 MeowUI / EzHookTool / HyperCeiler 一致 —— 复合构建也要求工具链对齐,见 §11 |
| MeowUI 目标分支 | `test`(`0.1.5-rc01`) | submodule 指针钉 `test`,与 MeowUI 接 miuix 的方式一致 |
| 设置页展示适配版本 | **要展示,已定** | 做法见 §7:元信息与 key 绑成 `Feature` 放 `:shared`,UI 用 `MeowFeaturePreference` 统一渲染 |
| `:ui` 内部结构 | **未设计** | 本文只写到「设置界面(MeowUI)」。XiaomiHelper 的 UI 侧有 83 个文件(`screen/` + `state/*ViewModel` + `repository/` + `di/`),功能一多就要这套。等第一批页面成形再定,不要现在空转 |

### 已否决:拆 `meowui-core`

约束是**发布出去的制品数要少**,不是「不许有内部模块」。所以问题变成:
能不能同时拿到「编译期分离」和「只发两个制品」?**算过了,没有干净的路。**

要只发两个制品,`core` 的 class 就必须物理上在 `meowui` 的 AAR 里。三条路各自的死点:

| 做法 | 制品数 | 死点 |
|---|---|---|
| 独立 Gradle 模块 + 发布 | 3 | 直接违反约束 |
| 独立模块 + 不发布,`meowui` 声明它为依赖 | 2 | `meowui` 的 POM 会指向一个仓库里不存在的坐标,Maven 消费方直接挂 |
| 独立模块 + 不发布,把 class 打进 `meowui` 的 AAR | 2 | Gradle/AGP 没有干净办法做这件事;fat-aar 类插件脆弱且吃 AGP 版本,AGP 9.3.1 上风险不值得 |
| 退化:`sourceSets.srcDir("../meowui-core/src/main/kotlin")` | 2 | 能编过,但那只是把目录挪个位置,**拿不到编译器强制** —— 和「一个模块 + 一条检查」等价,白折腾 |

复合构建这条也走不通:MeowCeiler 走 submodule + `includeBuild`,坐标替换确实能解析到未发布的
project,所以**对 MeowCeiler 自己是够用的**;但 MeowUI 将来发 Maven 时那个模块必须先被收回去 ——
等于为了一点编译期便利,给 MeowUI 背一笔有截止日期的债。

**结论:不拆。**「hook 侧不靠 UI」由 §4 末尾那条 import 检查保证 —— 十几行,效果接近,
而且完全在 MeowCeiler 这边,不动 MeowUI 的发布结构。

`e2d35b9` 那次把 `core` / `blur` / `libxposed` 从五个模块收成两个,和这条约束是同一个决定。
