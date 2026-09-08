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

/**
 * 三 Tab 外壳。
 *
 * scaffold 归外壳所有，pager 只装内容 —— 顶栏与底栏因此不跟着左右滑动。
 * 根页面不得自带 scaffold，它们拿到的是 [LocalShellContentPadding]。
 *
 * [LocalSideRail] 非空时底栏换成左侧栏，[floatingNavigation] 这时不起作用。
 */
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

    // 记下来而不是每趟重组重建：这一份要传给底栏或侧栏，换新实例它们就得整条重画。
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

/** 外壳发布的内容内边距。根页面自己 padding，而不是由外壳裁掉可滚动区域。 */
val LocalShellContentPadding = compositionLocalOf { PaddingValues(0.dp) }

/**
 * 侧边导航栏的展开状态，null 表示这一档不用侧栏。
 *
 * 该不该用侧栏由 [AdaptiveAppNavHost] 定，外壳自己量不出来：宽屏时它只占左栏那一列，
 * 量到的宽度永远小于分栏阈值。状态也归那边拿着，左栏宽度要跟着侧栏一起变。
 */
internal val LocalSideRail = compositionLocalOf<MeowNavigationRailState?> { null }
