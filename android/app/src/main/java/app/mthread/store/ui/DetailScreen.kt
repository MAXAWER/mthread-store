package app.mthread.store.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mthread.store.data.CatalogApp
import app.mthread.store.install.formatSize
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(app: CatalogApp, state: UiState, vm: StoreViewModel, snackbar: SnackbarHostState) {
    var viewer by remember { mutableStateOf<Int?>(null) }
    val installed = state.installed[app.id]
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { vm.select(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 80.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(app.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        if (app.encrypted) { Spacer(Modifier.width(6.dp)); LockBadge() }
                    }
                    if (app.category.isNotBlank()) {
                        Text(app.category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "Версия ${app.versionName} · ${formatSize(app.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (installed != null && app.versionCode > installed) {
                        Text("Установлена более старая версия", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            ActionArea(app, state, vm, Modifier.padding(20.dp))

            if (installed != null) {
                val enabled = app.id !in state.settings.excluded
                ListItem(
                    modifier = Modifier.clickable { vm.setAppAutoUpdate(app, !enabled) }.padding(horizontal = 4.dp),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Автообновление") },
                    supportingContent = {
                        Text(if (state.settings.autoUpdate) "Ставить новые версии этого приложения автоматически" else "Выключено во всех настройках")
                    },
                    trailingContent = { Switch(checked = enabled && state.settings.autoUpdate, onCheckedChange = null, enabled = state.settings.autoUpdate) },
                )
            }

            if (app.screenshots.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(app.screenshots) { index, path ->
                        AsyncImage(
                            model = mediaUrl(path),
                            contentDescription = null,
                            contentScale = ContentScale.FillHeight,
                            modifier = Modifier.height(260.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { viewer = index },
                        )
                    }
                }
            }

            if (app.changelog.isNotBlank()) Section("Что нового", app.changelog)
            if (app.description.isNotBlank()) Section("Описание", app.description)
            Section("Информация", buildString {
                append("Пакет: ${app.id}\n")
                append("Версия: ${app.versionName} (${app.versionCode})\n")
                installed?.let { append("Установлена: $it\n") }
                append("Размер: ${formatSize(app.size)}")
                app.updated?.take(10)?.let { append("\nОбновлено: $it") }
            })
        }
    }

    viewer?.let { start -> ScreenshotViewer(app.screenshots, start) { viewer = null } }
}

@Composable
private fun ActionArea(app: CatalogApp, state: UiState, vm: StoreViewModel, modifier: Modifier) {
    val task = state.tasks[app.id]
    val installed = state.installed[app.id]
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (task != null) {
            val f = task.fraction
            if (task.waiting) LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
            else if (f != null && task.phase != Phase.Confirm) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.label(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (task.waiting) {
                    androidx.compose.material3.FilledTonalButton(onClick = { vm.install(app) }) { Text("Скачать сейчас") }
                    Spacer(Modifier.width(8.dp))
                }
                if (task.phase == Phase.Queued || task.phase == Phase.Download) {
                    OutlinedButton(onClick = { vm.cancel(app) }) { Text("Отмена") }
                }
            }
        } else {
            val action = actionFor(app, installed)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (installed != null) {
                    OutlinedButton(onClick = { vm.uninstall(app) }, modifier = Modifier.weight(1f)) { Text("Удалить") }
                }
                Button(
                    onClick = { if (action == Action.Open) vm.open(app) else vm.install(app) },
                    modifier = Modifier.weight(1f),
                ) { Text(action.label()) }
            }
        }
    }
}

@Composable
private fun Section(title: String, text: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScreenshotViewer(paths: List<String>, start: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { paths.size }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = mediaUrl(paths[page]),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
