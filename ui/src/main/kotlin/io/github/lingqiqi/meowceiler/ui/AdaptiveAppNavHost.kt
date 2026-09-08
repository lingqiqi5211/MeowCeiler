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

/** 各窗口档位共用返回栈；启用悬浮底栏时不显示侧栏。 */
@Composable
internal fun AdaptiveAppNavHost(
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
    // 侧栏展开时增加左栏总宽度，保持主页面内容宽度。
    val railWidth = when {
        rail == null -> 0.dp
        railState.isExpanded -> MeowNavigationRailDefaults.ExpandedWidth
        else -> MeowNavigationRailDefaults.CollapsedWidth
    }

    MeowAdaptiveLayout(
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

/** 主页面内容宽度，不含侧边导航栏。 */
private val ShellContentWidth = 340.dp
