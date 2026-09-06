package io.github.lingqiqi.meowceiler.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lingqiqi5211.meowui.component.MeowNavigationBar
import io.github.lingqiqi5211.meowui.component.MeowNavigationBarStyle
import io.github.lingqiqi5211.meowui.component.MeowNavigationItem
import io.github.lingqiqi5211.meowui.component.MeowScaffold
import kotlinx.coroutines.launch
import kotlin.math.abs

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
 */
@Composable
fun ShellPage(
    floatingNavigation: Boolean,
    home: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    about: @Composable () -> Unit,
) {
    val tabs = ShellTab.entries
    val pagerState = rememberPagerState(pageCount = tabs::size)
    val scope = rememberCoroutineScope()
    val stateHolder = rememberSaveableStateHolder()
    var selectedTab by remember { mutableIntStateOf(pagerState.currentPage) }

    // 底栏先选中目标，避免跨页途中的 currentPage 把胶囊拉回中间项。
    // 内容页停止滚动后再同步，也覆盖手动滑动和打断切页动画的情况。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }
            .collect { (scrolling, page) ->
                if (!scrolling) selectedTab = page
            }
    }

    val items = listOf(
        MeowNavigationItem(stringResource(R.string.tab_home), Icons.Outlined.Home),
        MeowNavigationItem(stringResource(R.string.tab_settings), Icons.Outlined.Settings),
        MeowNavigationItem(stringResource(R.string.tab_about), Icons.Outlined.Info),
    )

    MeowScaffold(
        title = stringResource(tabs[pagerState.currentPage].titleRes),
        bottomBar = {
            MeowNavigationBar(
                items = items,
                selectedIndex = selectedTab,
                style = if (floatingNavigation) {
                    MeowNavigationBarStyle.Floating
                } else {
                    MeowNavigationBarStyle.Standard
                },
                onItemSelected = { target ->
                    selectedTab = target
                    scope.launch { pagerState.slideTo(target) }
                },
            )
        },
    ) { contentPadding ->
        CompositionLocalProvider(LocalShellContentPadding provides contentPadding) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = tabs.lastIndex,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(tabs.lastIndex),
                ),
            ) { page ->
                stateHolder.SaveableStateProvider(tabs[page].name) {
                    when (tabs[page]) {
                        ShellTab.Home -> home()
                        ShellTab.Settings -> settings()
                        ShellTab.About -> about()
                    }
                }
            }
        }
    }
}

/**
 * 按像素走而不是 animateScrollToPage：跨两页时时长随距离增加，观感与逐页滑动一致。
 */
private suspend fun androidx.compose.foundation.pager.PagerState.slideTo(target: Int) {
    val pageSize = layoutInfo.pageSize
    val distance = abs(target - currentPage)
    if (pageSize <= 0 || distance == 0) {
        scrollToPage(target)
        return
    }
    val from = currentPage + currentPageOffsetFraction
    animateScrollBy(
        value = (target - from) * pageSize,
        animationSpec = tween(100 * distance + 100, easing = EaseInOut),
    )
    scrollToPage(target)
}

/** 外壳发布的内容内边距。根页面自己 padding，而不是由外壳裁掉可滚动区域。 */
val LocalShellContentPadding = compositionLocalOf { PaddingValues(0.dp) }
