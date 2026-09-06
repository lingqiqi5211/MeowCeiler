package io.github.lingqiqi.meowceiler.ui.page

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.host.appIconSizePx
import io.github.lingqiqi.meowceiler.ui.host.rememberAppIcon
import io.github.lingqiqi.meowceiler.ui.settings.FrameworkBridge
import io.github.lingqiqi.meowceiler.ui.settings.ScopeState
import io.github.lingqiqi5211.meowui.component.MeowAlertDialog
import io.github.lingqiqi5211.meowui.component.MeowCard
import io.github.lingqiqi5211.meowui.component.MeowScaffold
import io.github.lingqiqi5211.meowui.component.MeowMenuItem
import io.github.lingqiqi5211.meowui.component.MeowSearchBar
import io.github.lingqiqi5211.meowui.component.MeowTopBarAction
import io.github.lingqiqi5211.meowui.theme.MeowIcons
import io.github.lingqiqi5211.meowui.component.meowScaffoldScroll
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * 作用域：列出全部已安装应用，点一条就是加入或移出。
 *
 * 已在作用域里的排最前，其余按名称排。加包要用户在管理器里点同意，所以每次改完都重新读一遍框架，
 * 以框架里的实际结果为准，不拿本地状态假装成功。
 */
@Composable
fun ScopePage(
    bridge: FrameworkBridge,
    scopeState: ScopeState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val apps = rememberInstalledApps()
    val inScope = scopeState.packages.orEmpty()

    var filter by remember { mutableStateOf(ScopeFilter.All) }
    val listState = rememberLazyListState()
    // 收起动画播完之前两份列表同时挂着，LazyListState 不能共用，搜索面自己滚自己的。
    val searchListState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val frameworkFailed = stringResource(R.string.settings_framework_failed)

    val toggle: (String, Boolean) -> Unit = { packageName, enabled ->
        scope.launch {
            bridge.setInScope(packageName, enabled)
                .onFailure { error = frameworkFailed.format(it.message.orEmpty()) }
            scopeState.reload()
        }
    }

    val rows = remember(apps, inScope, filter) {
        val collator = Collator.getInstance()
        apps.orEmpty()
            .filter { filter.accepts(it, it.packageName in inScope) }
            .sortedWith(
                compareByDescending<ScopeApp> { it.packageName in inScope }
                    .thenBy(collator) { it.label }
                    .thenBy { it.packageName },
            )
    }
    val matches = remember(rows, query) {
        val keyword = query.trim()
        rows.filter {
            it.label.contains(keyword, ignoreCase = true) ||
                it.packageName.contains(keyword, ignoreCase = true)
        }
    }

    MeowScaffold(
        title = stringResource(R.string.settings_scope),
        onBackClick = onBack,
        actionItems = listOf(
            MeowTopBarAction.Menu(
                icon = MeowIcons.Filter,
                contentDescription = stringResource(R.string.settings_scope_filter),
                modifier = Modifier.testTag("action.scope.filter"),
                // 单选式菜单：点完不收，方便连着比几种筛选。
                collapseOnSelection = false,
                items = ScopeFilter.entries.map { entry ->
                    MeowMenuItem(
                        text = stringResource(entry.labelRes),
                        selected = entry == filter,
                        onClick = { filter = entry },
                    )
                },
            ),
        ),
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .meowScaffoldScroll()
                .padding(
                    start = 16.dp,
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    end = 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 搜索面里铺的是同一份列表，收起来时结果还在原处。
            MeowSearchBar(
                query = query,
                onQueryChange = { query = it },
                expanded = searchExpanded,
                onExpandedChange = { searchExpanded = it },
                placeholder = stringResource(R.string.settings_scope_search),
                cancelText = stringResource(R.string.dialog_cancel),
            ) {
                // 空查询词不铺结果：整份列表不是搜索结果。
                if (query.isNotBlank()) {
                    ScopeList(
                        apps = matches,
                        inScope = inScope,
                        loading = apps == null,
                        onToggle = toggle,
                        listState = searchListState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (searchExpanded) return@Column
            ScopeList(
                apps = rows,
                inScope = inScope,
                loading = apps == null,
                onToggle = toggle,
                listState = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 12.dp),
            )
        }
    }

    MeowAlertDialog(
        show = error != null,
        title = stringResource(R.string.settings_scope),
        message = error.orEmpty(),
        onConfirm = { error = null },
        onDismissRequest = { error = null },
        cancelText = null,
    )
}

@Composable
private fun ScopeList(
    apps: List<ScopeApp>,
    inScope: Set<String>,
    loading: Boolean,
    onToggle: (String, Boolean) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        state = listState,
        modifier = modifier.testTag("list.scope"),
        contentPadding = contentPadding,
    ) {
        if (apps.isEmpty()) {
            item {
                BasicText(
                    text = stringResource(
                        if (loading) R.string.settings_scope_loading else R.string.settings_scope_empty,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurfaceVariant),
                )
            }
            return@LazyColumn
        }
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            ScopeAppRow(
                app = app,
                index = index,
                count = apps.size,
                granted = app.packageName in inScope,
                onToggle = onToggle,
            )
        }
    }
}

@Composable
private fun ScopeAppRow(
    app: ScopeApp,
    index: Int,
    count: Int,
    granted: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    MeowCard(
        modifier = Modifier.fillMaxWidth().testTag("row.scope.${app.packageName}"),
        index = index,
        count = count,
        onClick = { onToggle(app.packageName, !granted) },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(
                    text = app.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MeowTheme.typography.title.copy(color = MeowTheme.colors.onSurface),
                )
                BasicText(
                    text = app.packageName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurfaceVariant),
                )
            }
            Image(
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = stringResource(
                    if (granted) R.string.settings_scope_granted else R.string.settings_scope_not_granted,
                ),
                modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(
                    if (granted) MeowTheme.colors.primary else MeowTheme.colors.outline,
                ),
            )
        }
    }
}

/** 图标随行加载：列表几百条，一次性全部解码既慢又占内存。加载期间槽位先占着，行不会跳。 */
@Composable
private fun AppIcon(packageName: String) {
    val icon = rememberAppIcon(packageName, appIconSizePx(AppIconSize))
    Box(modifier = Modifier.size(AppIconSize)) {
        icon?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(AppIconSize))
        }
    }
}

private val AppIconSize: Dp = 42.dp

private data class ScopeApp(val packageName: String, val label: String, val system: Boolean)

private enum class ScopeFilter(val labelRes: Int) {
    All(R.string.settings_scope_filter_all),
    InScope(R.string.settings_scope_filter_in),
    NotInScope(R.string.settings_scope_filter_out),
    User(R.string.settings_scope_filter_user),
    System(R.string.settings_scope_filter_system),
    ;

    fun accepts(app: ScopeApp, granted: Boolean): Boolean = when (this) {
        All -> true
        InScope -> granted
        NotInScope -> !granted
        User -> !app.system
        System -> app.system
    }
}

/** 读全部已安装应用。名称查表不便宜，放 IO 线程；读完之前给 null，列表据此显示「读取中」。 */
@Composable
private fun rememberInstalledApps(): List<ScopeApp>? {
    val context = LocalContext.current
    val apps by produceState<List<ScopeApp>?>(null, context) {
        value = withContext(Dispatchers.IO) { context.loadInstalledApps() }
    }
    return apps
}

private fun Context.loadInstalledApps(): List<ScopeApp> = runCatching {
    val pm = packageManager
    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        .filter(ApplicationInfo::enabled)
        .map {
            ScopeApp(
                packageName = it.packageName,
                label = pm.getApplicationLabel(it).toString(),
                system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }
}.getOrDefault(emptyList())
