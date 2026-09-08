package io.github.lingqiqi.meowceiler.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lingqiqi5211.meowui.component.MeowNavigationBar
import io.github.lingqiqi5211.meowui.component.MeowNavigationBarStyle
import io.github.lingqiqi5211.meowui.component.MeowNavigationItem
import io.github.lingqiqi5211.meowui.component.MeowNavigationPager
import io.github.lingqiqi5211.meowui.component.MeowNavigationRail
import io.github.lingqiqi5211.meowui.component.MeowNavigationRailState
import io.github.lingqiqi5211.meowui.component.MeowScaffold
import io.github.lingqiqi5211.meowui.component.rememberMeowNavigationSelection

private enum class ShellTab(val titleRes: Int) {
    Home(R.string.app_name),
    Settings(R.string.tab_settings),
    About(R.string.tab_about),
}

/** 外壳持有 Scaffold；根页面通过 [LocalShellContentPadding] 获取内边距。 */
@Composable
fun ShellPage(
    floatingNavigation: Boolean,
    home: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    about: @Composable () -> Unit,
    onTabChanged: () -> Unit = {},
) {
    val tabs = ShellTab.entries
    val pagerState = rememberPagerState(pageCount = tabs::size)
    val selection = rememberMeowNavigationSelection(pagerState) { onTabChanged() }

    val homeLabel = stringResource(R.string.tab_home)
    val settingsLabel = stringResource(R.string.tab_settings)
    val aboutLabel = stringResource(R.string.tab_about)
    val items = remember(homeLabel, settingsLabel, aboutLabel) {
        listOf(
            MeowNavigationItem(homeLabel, Icons.Outlined.Home),
            MeowNavigationItem(settingsLabel, Icons.Outlined.Settings),
            MeowNavigationItem(aboutLabel, Icons.Outlined.Info),
        )
    }

    val sideRail = LocalSideRail.current
    val rail: (@Composable () -> Unit)? =
        if (sideRail != null) {
            {
                MeowNavigationRail(
                    items = items,
                    selectedIndex = selection.index,
                    onItemSelected = selection::select,
                    state = sideRail,
                )
            }
        } else {
            null
        }

    MeowScaffold(
        title = stringResource(tabs[pagerState.currentPage].titleRes),
        bottomBar = {
            if (sideRail == null) {
                MeowNavigationBar(
                    items = items,
                    selectedIndex = selection.index,
                    style = if (floatingNavigation) {
                        MeowNavigationBarStyle.Floating
                    } else {
                        MeowNavigationBarStyle.Standard
                    },
                    onItemSelected = selection::select,
                )
            }
        },
        navigationRail = rail,
    ) { contentPadding ->
        CompositionLocalProvider(LocalShellContentPadding provides contentPadding) {
            MeowNavigationPager(selection) { page ->
                when (tabs[page]) {
                    ShellTab.Home -> home()
                    ShellTab.Settings -> settings()
                    ShellTab.About -> about()
                }
            }
        }
    }
}

val LocalShellContentPadding = compositionLocalOf { PaddingValues(0.dp) }

/** 侧栏状态由完整窗口决定；分栏后的 Shell 宽度不能用于判断设备布局。null 表示不用侧栏。 */
internal val LocalSideRail = compositionLocalOf<MeowNavigationRailState?> { null }
