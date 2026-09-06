package io.github.lingqiqi.meowceiler.ui.page

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withContext
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi5211.meowui.component.MeowBottomSheet
import io.github.lingqiqi5211.meowui.component.MeowButton
import io.github.lingqiqi5211.meowui.component.MeowCard
import io.github.lingqiqi5211.meowui.component.MeowScaffold
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import kotlinx.coroutines.Dispatchers
import java.text.Collator
import kotlinx.coroutines.withContext as onDispatcher

/**
 * 开源许可。清单由 AboutLibraries 的 Gradle 插件在构建时生成，打进 `res/raw/aboutlibraries.json`。
 *
 * 列表走 LazyColumn：一百多条依赖，用整屏滚动的 Column 会在进页面那一帧全部组合完，明显卡一下。
 * 点一条从底部抽屉看许可证全文。
 */
@Composable
fun LicensesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val libraries by produceState<List<Library>?>(null, context) {
        value = onDispatcher(Dispatchers.IO) { context.loadLibraries() }
    }
    var selected by remember { mutableStateOf<Library?>(null) }

    MeowScaffold(
        title = stringResource(R.string.about_licenses),
        onBackClick = onBack,
    ) { contentPadding ->
        val list = libraries
        if (list.isNullOrEmpty()) {
            BasicText(
                text = stringResource(
                    if (list == null) R.string.licenses_loading else R.string.licenses_empty,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding)
                    .padding(vertical = 24.dp),
                style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurfaceVariant),
            )
            return@MeowScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("list.licenses"),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list, key = { it.uniqueId }) { library ->
                LibraryCard(library) { selected = library }
            }
        }
    }

    // 收起动画期间 selected 已经是 null，内容仍要画着，否则抽屉是空的滑下去。
    var lastShown by remember { mutableStateOf<Library?>(null) }
    selected?.let { lastShown = it }
    val library = lastShown
    MeowBottomSheet(
        show = selected != null,
        onDismissRequest = { selected = null },
        title = library?.name.orEmpty(),
    ) {
        library ?: return@MeowBottomSheet
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            library.licenses.forEach { license ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MeowTheme.colors.surfaceVariant, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText(
                        text = license.name,
                        modifier = Modifier.clickable(enabled = !license.url.isNullOrBlank()) {
                            license.url?.let(uriHandler::openUri)
                        },
                        style = MeowTheme.typography.title.copy(color = MeowTheme.colors.primary),
                    )
                    BasicText(
                        text = license.licenseContent?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.licenses_no_text),
                        style = MeowTheme.typography.summary.copy(
                            color = MeowTheme.colors.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            library.link?.let { url ->
                MeowButton(
                    text = stringResource(R.string.licenses_visit),
                    onClick = { uriHandler.openUri(url) },
                    modifier = Modifier.weight(1f),
                )
            }
            MeowButton(
                text = stringResource(R.string.licenses_close),
                onClick = { selected = null },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LibraryCard(library: Library, onClick: () -> Unit) {
    MeowCard(
        modifier = Modifier.fillMaxWidth().testTag("row.license.${library.uniqueId}"),
        onClick = onClick,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = library.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MeowTheme.typography.title.copy(color = MeowTheme.colors.onSurface),
            )
            library.artifactVersion?.takeIf(String::isNotBlank)?.let { version ->
                BasicText(
                    text = version,
                    style = MeowTheme.typography.summary.copy(
                        color = MeowTheme.colors.onSurfaceVariant,
                    ),
                )
            }
        }
        library.author()?.let { author ->
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = author,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface),
            )
        }
        val licenses = library.licenses.map { it.spdxId?.takeIf(String::isNotBlank) ?: it.name }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val shown = licenses.ifEmpty { listOf(stringResource(R.string.licenses_unknown)) }
            shown.forEach { name -> LicenseChip(name) }
        }
    }
}

@Composable
private fun LicenseChip(name: String) {
    Box(
        modifier = Modifier
            .background(
                MeowTheme.colors.primaryContainer.copy(alpha = 0.2f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        BasicText(
            text = name,
            style = MeowTheme.typography.summary.copy(
                color = MeowTheme.colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/** 开发者，没有就退到组织名。 */
private fun Library.author(): String? =
    developers.mapNotNull { it.name?.takeIf(String::isNotBlank) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?: organization?.name?.takeIf(String::isNotBlank)

/** 主页优先，没有就退到源码仓库。 */
private val Library.link: String?
    get() = website?.takeIf(String::isNotBlank) ?: scm?.url?.takeIf(String::isNotBlank)

private fun Context.loadLibraries(): List<Library> = runCatching {
    val collator = Collator.getInstance()
    Libs.Builder().withContext(this).build().libraries.sortedWith(compareBy(collator) { it.name })
}.getOrDefault(emptyList())
