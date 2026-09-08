package io.github.lingqiqi.meowceiler.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lingqiqi5211.meowui.component.MeowAdaptiveLayout
import io.github.lingqiqi5211.meowui.component.MeowCompactPane
import io.github.lingqiqi5211.meowui.component.MeowNavHost
import io.github.lingqiqi5211.meowui.component.MeowNavigationRailDefaults
import io.github.lingqiqi5211.meowui.component.MeowWindowWidth
import io.github.lingqiqi5211.meowui.component.rememberMeowNavigationRailState
import io.github.lingqiqi5211.meowui.theme.MeowTheme

/**
 * 按窗口大小分三档：
 *
 * - 窄：整屏一页，导航栏在底部。手机竖屏。
 * - 中：导航栏移到左侧，仍然整屏一页。手机横屏、竖屏平板、展开的折叠机都在这一档。
 * - 宽：左侧栏 + 分两栏，导航栈摊在外壳右侧。横屏平板。
 *
 * 三档共用一个返回栈，宽屏因此不需要另一套导航状态：栈底永远是外壳，其余是详情。
 * [floatingNavigation] 开着时不出侧栏：悬浮底栏是用户挑的样子，宽屏也留在底下。
 */
@Composable
internal fun AdaptiveAppNavHost(
    // 收 SnapshotStateList 而不是 List：List 在编译器眼里不稳定，下面几个 @Composable lambda
    // 就不会被记住，每次重组都换新的，分栏两侧跟着整棵重新组合。
    backStack: SnapshotStateList<Route>,
    onBack: () -> Unit,
    predictiveBackEnabled: Boolean,
    floatingNavigation: Boolean,
    content: @Composable (Route) -> Unit,
) {
    val shell: @Composable () -> Unit = { content(Route.Shell) }
    val navHost: @Composable (@Composable (Route) -> Unit) -> Unit = { page ->
        MeowNavHost(
            backStack = backStack,
            onBack = onBack,
            predictiveBackEnabled = predictiveBackEnabled,
            content = page,
        )
    }
    val railState = rememberMeowNavigationRailState()
    val rail = if (floatingNavigation) null else railState
    // 左栏跟着侧栏一起变宽，否则展开多出来的那一截是从外壳内容里抠的，列表会被挤扁。
    // 这里只给目标值，过渡由 MeowAdaptiveLayout 在布局期做。
    val railWidth = when {
        rail == null -> 0.dp
        railState.isExpanded -> MeowNavigationRailDefaults.ExpandedWidth
        else -> MeowNavigationRailDefaults.CollapsedWidth
    }

    MeowAdaptiveLayout(
        // 给了 compactContent，窄屏归它管，compactPane 只是形参。
        compactPane = MeowCompactPane.List,
        listPane = { CompositionLocalProvider(LocalSideRail provides rail) { shell() } },
        detailPane = { navHost { route -> if (route == Route.Shell) DetailPlaceholder() else content(route) } },
        listPaneWidth = ShellContentWidth + railWidth,
        compactContent = { width ->
            val compactRail = if (width >= MeowWindowWidth.Medium) rail else null
            CompositionLocalProvider(LocalSideRail provides compactRail) {
                navHost { route -> if (route == Route.Shell) shell() else content(route) }
            }
        },
    )
}

/** 详情栏还没有选中项。 */
@Composable
private fun DetailPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = stringResource(R.string.adaptive_select_detail),
            style = MeowTheme.typography.summary.copy(
                color = MeowTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** 左栏里留给外壳内容的宽度，侧边导航栏那一列另算。 */
private val ShellContentWidth = 340.dp
