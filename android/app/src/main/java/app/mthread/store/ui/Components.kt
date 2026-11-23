package app.mthread.store.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mthread.store.StoreApp
import app.mthread.store.data.CatalogApp
import app.mthread.store.install.formatSize
import coil3.compose.AsyncImage

@Composable
fun mediaUrl(path: String?): String? = (LocalContext.current.applicationContext as StoreApp).catalog.mediaUrl(path)

@Composable
fun AppIcon(app: CatalogApp, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.26f)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val url = mediaUrl(app.icon)
        if (url == null) {
            Icon(Icons.Outlined.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.5f))
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

enum class Action { Install, Update, Open }

fun actionFor(app: CatalogApp, installed: Long?) = when {
    installed == null -> Action.Install
    app.versionCode > installed -> Action.Update
    else -> Action.Open
}

fun Action.label() = when (this) {
    Action.Install -> "Установить"
    Action.Update -> "Обновить"
    Action.Open -> "Открыть"
}

fun AppTask.label(): String = when (phase) {
    Phase.Queued -> if (waiting) "Ждёт Wi-Fi или зарядки" else "В очереди…"
    Phase.Download -> if (total > 0) "${formatSize(done)} из ${formatSize(total)} · ${(done * 100 / total)}%" else "Скачивание…"
    Phase.Install -> "Установка…"
    Phase.Confirm -> "Подтвердите установку"
}

/** Строка приложения в списках: иконка, название, подпись и кнопка действия. */
@Composable
fun AppListItem(app: CatalogApp, state: UiState, vm: StoreViewModel, supporting: String? = null) {
    val task = state.tasks[app.id]
    val action = actionFor(app, state.installed[app.id])
    ListItem(
        modifier = Modifier.clickable { vm.select(app.id) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { AppIcon(app, 52.dp) },
        headlineContent = {
            Text(app.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                val line = supporting ?: app.short.ifBlank { app.category }
                if (line.isNotBlank()) Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    task?.label() ?: buildString {
                        append(app.versionName)
                        append(" · ")
                        append(formatSize(app.size))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            when {
                task != null && !task.waiting -> Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    val f = task.fraction
                    if (f != null && task.phase == Phase.Download) {
                        CircularProgressIndicator(progress = { f }, modifier = Modifier.size(32.dp))
                    } else {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                }
                action == Action.Open -> FilledTonalButton(onClick = { vm.open(app) }) { Text(action.label()) }
                else -> Button(onClick = { vm.install(app) }) { Text(if (task?.waiting == true) "Сейчас" else action.label()) }
            }
        },
    )
}

@Composable
fun LockBadge() {
    Icon(Icons.Outlined.Lock, "Закрытое", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
