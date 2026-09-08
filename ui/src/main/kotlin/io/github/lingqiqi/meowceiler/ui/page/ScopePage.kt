package io.github.lingqiqi.meowceiler.ui.page

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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.host.InstalledApp
import io.github.lingqiqi.meowceiler.ui.host.appIconSizePx
import io.github.lingqiqi.meowceiler.ui.host.rememberAppIcon
import io.github.lingqiqi.meowceiler.ui.host.rememberInstalledApps
import io.github.lingqiqi.meowceiler.ui.settings.FrameworkBridge
import io.github.lingqiqi.meowceiler.ui.settings.ScopeState
import io.github.lingqiqi5211.meowui.component.MeowAlertDialog
import io.github.lingqiqi5211.meowui.component.MeowCard
import io.github.lingqiqi5211.meowui.component.MeowLoadingIndicator
import io.github.lingqiqi5211.meowui.component.MeowMenuItem
import io.github.lingqiqi5211.meowui.component.MeowPullToRefresh
import io.github.lingqiqi5211.meowui.component.MeowScaffold
import io.github.lingqiqi5211.meowui.component.MeowSearchBar
import io.github.lingqiqi5211.meowui.component.MeowTopBarAction
import io.github.lingqiqi5211.meowui.theme.MeowIcons
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import kotlinx.coroutines.launch
import java.text.Collator

/** 作用域变更后重新读取框架结果；申请未获批准时不能视为已加入。 */
@Composable
fun ScopePage(
    bridge: FrameworkBridge,
    scopeState: ScopeState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val installed = rememberInstalledApps()
    val apps = installed.apps
    val inScope = scopeState.packages.orEmpty()

    var filter by remember { mutableStateOf(ScopeFilter.All) }
    val listState = rememberLazyListState()
    // 搜索收起时两份列表同时存在，不能共用 LazyListState。
    val searchListState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val frameworkFailed by rememberUpdatedState(stringResource(R.string.settings_framework_failed))
    val toggle: (String, Boolean) -> Unit = remember(bridge, scopeState, scope) {
        { packageName, enabled ->
            scope.launch {
                bridge.setInScope(packageName, enabled)
                    .onFailure { error = frameworkFailed.format(it.message.orEmpty()) }
                scopeState.reload()
            }
            Unit
        }
    }
    val refresh = remember(installed, scopeState) {
        {
            installed.refresh()
            scopeState.reload()
        }
    }
    val pullText = stringResource(R.string.pull_refresh_pull)
    val releaseText = stringResource(R.string.pull_refresh_release)
    val refreshingText = stringResource(R.string.pull_refresh_refreshing)
    val refreshedText = stringResource(R.string.pull_refresh_done)
    val refreshTexts = remember(pullText, releaseText, refreshingText, refreshedText) {
        listOf(pullText, releaseText, refreshingText, refreshedText)
    }

    val rows = remember(apps, inScope, filter) {
        val collator = Collator.getInstance()
        apps.orEmpty()
            .map { ScopeRow(it, it.packageName in inScope) }
            .filter(filter::accepts)
            .sortedWith(
                compareByDescending<ScopeRow> { it.granted }
                    .thenBy(collator) { it.app.label }
                    .thenBy { it.app.packageName },
            )
    }
    val matches = remember(rows, query) {
        val keyword = query.trim()
        rows.filter {
            it.app.label.contains(keyword, ignoreCase = true) ||
                it.app.packageName.contains(keyword, ignoreCase = true)
        }
    }

    val filterAction = MeowTopBarAction.Menu(
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
    )

    MeowScaffold(
        title = stringResource(R.string.settings_scope),
        onBackClick = onBack,
        actionItems = listOf(filterAction),
    ) { contentPadding ->
        // MeowPullToRefresh 已连接顶栏滚动，外层不再重复连接。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 16.dp,
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    end = 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MeowSearchBar(
                query = query,
                onQueryChange = { query = it },
                expanded = searchExpanded,
                onExpandedChange = { searchExpanded = it },
                placeholder = stringResource(R.string.settings_scope_search),
                cancelText = stringResource(R.string.dialog_cancel),
            ) {
                if (query.isNotBlank()) {
                    ScopeList(
                        rows = matches,
                        loading = apps == null,
                        onToggle = toggle,
                        listState = searchListState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (searchExpanded) return@Column
            MeowPullToRefresh(
                isRefreshing = installed.loading && apps != null,
                onRefresh = refresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("refresh.scope"),
                // 内容已包含顶栏内边距，刷新指示器不再重复偏移。
                scaffoldPadding = PaddingValues(0.dp),
                refreshTexts = refreshTexts,
            ) {
                ScopeList(
                    rows = rows,
                    loading = apps == null,
                    onToggle = toggle,
                    listState = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 12.dp),
                )
            }
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
    rows: List<ScopeRow>,
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
        if (rows.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillParentMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        val description = stringResource(R.string.settings_scope_loading)
                        MeowLoadingIndicator(
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                    } else {
                        BasicText(
                            text = stringResource(R.string.settings_scope_empty),
                            style = MeowTheme.typography.summary.copy(
                                color = MeowTheme.colors.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
            return@LazyColumn
        }
        itemsIndexed(rows, key = { _, row -> row.app.packageName }) { index, row ->
            ScopeAppRow(
                row = row,
                index = index,
                count = rows.size,
                onToggle = onToggle,
            )
        }
    }
}

@Composable
private fun ScopeAppRow(
    row: ScopeRow,
    index: Int,
    count: Int,
    onToggle: (String, Boolean) -> Unit,
) {
    val app = row.app
    MeowCard(
        modifier = Modifier.fillMaxWidth().testTag("row.scope.${app.packageName}"),
        index = index,
        count = count,
        onClick = { onToggle(app.packageName, !row.granted) },
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
                imageVector = if (row.granted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = stringResource(
                    if (row.granted) R.string.settings_scope_granted else R.string.settings_scope_not_granted,
                ),
                modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(
                    if (row.granted) MeowTheme.colors.primary else MeowTheme.colors.outline,
                ),
            )
        }
    }
}

/** 按可见行加载图标，加载期间保留槽位尺寸。 */
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

@Immutable
private data class ScopeRow(val app: InstalledApp, val granted: Boolean)

private enum class ScopeFilter(val labelRes: Int) {
    All(R.string.settings_scope_filter_all),
    InScope(R.string.settings_scope_filter_in),
    NotInScope(R.string.settings_scope_filter_out),
    User(R.string.settings_scope_filter_user),
    System(R.string.settings_scope_filter_system),
    ;

    fun accepts(row: ScopeRow): Boolean = when (this) {
        All -> true
        InScope -> row.granted
        NotInScope -> !row.granted
        User -> !row.app.system
        System -> row.app.system
    }
}
