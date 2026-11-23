package app.mthread.store.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mthread.store.BuildConfig
import app.mthread.store.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: StoreViewModel) {
    val s = state.settings
    var intervalDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Настройки") })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Автообновление")
            SwitchItem(Icons.Outlined.Sync, "Обновлять автоматически",
                if (Build.VERSION.SDK_INT >= 31) "Без подтверждений для приложений, установленных через Store"
                else "На этой версии Android каждое обновление нужно будет подтвердить",
                s.autoUpdate) { v -> vm.updateSettings({ autoUpdate = v }) }
            SwitchItem(Icons.Outlined.Wifi, "Только по Wi-Fi", "Не тратить мобильный трафик", s.wifiOnly, enabled = s.autoUpdate) { v ->
                vm.updateSettings({ wifiOnly = v })
            }
            SwitchItem(Icons.Outlined.BatteryChargingFull, "Только на зарядке", "Обновлять, когда телефон заряжается",
                s.chargingOnly, enabled = s.autoUpdate) { v -> vm.updateSettings({ chargingOnly = v }) }
            ListItem(
                modifier = Modifier.clickable { intervalDialog = true },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.Schedule, null) },
                headlineContent = { Text("Проверять обновления") },
                supportingContent = { Text(intervalLabel(s.intervalHours)) },
            )
            SwitchItem(Icons.Outlined.Notifications, "Уведомлять об обновлениях", "Если их нельзя поставить автоматически",
                s.notifyUpdates) { v -> vm.updateSettings({ notifyUpdates = v }) }

            SectionHeader("Установка")
            ListItem(
                modifier = Modifier.clickable(enabled = !state.canInstall) { vm.requestInstallPermission() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.InstallMobile, null) },
                headlineContent = { Text("Установка приложений") },
                supportingContent = {
                    Text(if (state.canInstall) "Разрешена" else "Не разрешена — нажмите, чтобы разрешить",
                        color = if (state.canInstall) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                },
            )

            SectionHeader("О приложении")
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.Info, null) },
                headlineContent = { Text("MThread Store ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Каталог: ${BuildConfig.STORE_REPO}") },
            )
        }
    }

    if (intervalDialog) {
        AlertDialog(
            onDismissRequest = { intervalDialog = false },
            title = { Text("Проверять обновления") },
            text = {
                Column {
                    Settings.INTERVALS.forEach { h ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = h == s.intervalHours, role = Role.RadioButton) {
                                    vm.updateSettings({ checkIntervalHours = h }, reschedule = true)
                                    intervalDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = h == s.intervalHours, onClick = null)
                            Text(intervalLabel(h), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { intervalDialog = false }) { Text("Закрыть") } },
        )
    }
}

private fun intervalLabel(h: Int) = when (h) {
    1 -> "Каждый час"
    24 -> "Раз в сутки"
    else -> "Каждые $h ч"
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}
