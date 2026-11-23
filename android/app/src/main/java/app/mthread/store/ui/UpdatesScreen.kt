package app.mthread.store.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncDisabled
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(state: UiState, vm: StoreViewModel) {
    val updates = state.updates
    val upToDate = state.installedApps.filter { it !in updates }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Обновления") })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { AutoUpdateCard(state, vm) }

            if (updates.isEmpty()) {
                item {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("Всё обновлено") },
                    )
                }
            } else {
                item {
                    SectionHeader("Доступно: ${updates.size}") {
                        if (updates.size > 1) FilledTonalButton(onClick = vm::updateAll) { Text("Обновить все") }
                    }
                }
                items(updates, key = { "u:" + it.id }) { app ->
                    val from = state.installed[app.id]
                    AppListItem(app, state, vm, supporting = app.changelog.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: "Новая версия ${app.versionName}" + (from?.let { " (сейчас $it)" } ?: ""))
                }
            }

            if (upToDate.isNotEmpty()) {
                item { SectionHeader("Установлено: ${upToDate.size}") }
                items(upToDate, key = { "i:" + it.id }) { AppListItem(it, state, vm) }
            }

            if (state.history.isNotEmpty()) {
                item { SectionHeader("История") }
                items(state.history.take(15), key = { "h:${it.id}:${it.time}" }) { h ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        headlineContent = { Text(h.name) },
                        supportingContent = {
                            Text("${h.version} · ${DateUtils.getRelativeTimeSpanString(h.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoUpdateCard(state: UiState, vm: StoreViewModel) {
    val s = state.settings
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (s.autoUpdate) Icons.Outlined.Sync else Icons.Outlined.SyncDisabled, null,
                tint = if (s.autoUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (s.autoUpdate) "Автообновление включено" else "Автообновление выключено", style = MaterialTheme.typography.titleMedium)
                val details = buildList {
                    if (s.autoUpdate) add(if (s.wifiOnly) "только Wi-Fi" else "любая сеть")
                    if (s.autoUpdate && s.chargingOnly) add("на зарядке")
                    add("проверка каждые ${s.intervalHours} ч")
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (s.lastCheck == 0L) "Ещё не проверялось"
                    else "Проверено " + DateUtils.getRelativeTimeSpanString(s.lastCheck, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.checking) {
                CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
            } else {
                TextButton(onClick = vm::checkNow) { Text("Проверить") }
            }
        }
    }
}
