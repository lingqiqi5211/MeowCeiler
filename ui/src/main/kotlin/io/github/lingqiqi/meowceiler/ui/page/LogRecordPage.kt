package io.github.lingqiqi.meowceiler.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.lingqiqi.meowceiler.shared.HookLogRecord
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi5211.meowui.component.MeowPreferencePage
import io.github.lingqiqi5211.meowui.component.MeowTopBarAction
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogRecordPage(record: HookLogRecord, onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember(record) { record.fullText() }
    MeowPreferencePage(
        title = stringResource(R.string.log_record),
        onBackClick = onBack,
        actionItems = listOf(
            MeowTopBarAction.Icon(
                icon = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.log_copy),
                modifier = Modifier.testTag("action.log.copy"),
                onClick = { copyToClipboard(context, text) },
            ),
            MeowTopBarAction.Icon(
                icon = Icons.Filled.Share,
                contentDescription = stringResource(R.string.log_share),
                modifier = Modifier.testTag("action.log.share"),
                onClick = { shareAsFile(context, record, text) },
            ),
        ),
    ) {
        SettingsCard(testTag = "section.log.record") {
            item(key = "text") {
                // 堆栈按原样横向滚动：折行会把缩进和栈帧顺序搅在一起，读不出层级。
                SelectionContainer {
                    BasicText(
                        text = text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp)
                            .testTag("text.log.record"),
                        style = MeowTheme.typography.summary.copy(
                            color = MeowTheme.colors.onSurface,
                            fontFamily = FontFamily.Monospace,
                        ),
                        softWrap = false,
                    )
                }
            }
        }
    }
}

internal fun HookLogRecord.fullText(): String {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return buildString {
        append(format.format(Date(firstMillis)))
        if (lastMillis != firstMillis) append(" – ").append(format.format(Date(lastMillis)))
        if (count > 1) append("  ×").append(count).append('+')
        append('\n').append(host).append("  ").append(tag).append("  ").append(kind.name).append(' ').append(level)
        append("\n\n").append(message)
    }
}

internal fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("MeowCeiler", text))
}

private fun shareAsFile(context: Context, record: HookLogRecord, text: String) {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(record.lastMillis))
    val file = File(File(context.cacheDir, "share").apply { mkdirs() }, "meowceiler-${record.tag}-$stamp.txt")
    file.writeText(text)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
