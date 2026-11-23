package app.mthread.store.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mthread.store.CrashLog

class MainActivity : ComponentActivity() {
    private val vm: StoreViewModel by viewModels()
    private var crashMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // После падения сначала показываем отчёт — ViewModel не трогаем, чтобы не упасть снова.
        val crash = CrashLog.read(this)
        if (crash != null) {
            crashMode = true
            // Обычные View, без Compose и тем: экран отчёта должен открыться, даже если упал сам интерфейс.
            setContentView(crashView(crash))
            return
        }
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { StoreTheme { StoreRoot(vm) } }
    }

    private fun crashView(text: String): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        fun button(label: String, onClick: () -> Unit) = Button(this).apply { this.text = label; setOnClickListener { onClick() } }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Скопировать") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("crash", text))
                Toast.makeText(this@MainActivity, "Скопировано", Toast.LENGTH_SHORT).show()
            })
            addView(button("Отправить") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, "Отправить отчёт"))
            })
            addView(button("Продолжить") { CrashLog.clear(this@MainActivity); recreate() })
        }
        val body = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(pad, pad * 2, pad, pad)
            addView(TextView(this@MainActivity).apply { this.text = "Store упал при прошлом запуске.\nСкопируйте текст и пришлите разработчику."; textSize = 18f })
            addView(buttons)
            addView(ScrollView(this@MainActivity).apply { addView(HorizontalScrollView(this@MainActivity).apply { addView(body) }) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!crashMode) vm.refreshInstalled()
    }
}

@Composable
private fun StoreRoot(vm: StoreViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var askPermission by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbar.showSnackbar(event.text)
                UiEvent.NeedInstallPermission -> askPermission = true
                is UiEvent.Launch -> runCatching { context.startActivity(event.intent) }
            }
        }
    }

    BackHandler(enabled = state.selectedId != null || state.tab != Tab.Catalog) {
        if (state.selectedId != null) vm.select(null) else vm.setTab(Tab.Catalog)
    }

    val selected = state.selected
    AnimatedContent(targetState = selected, label = "detail", transitionSpec = { fadeIn() togetherWith fadeOut() }) { app ->
        if (app != null) {
            DetailScreen(app, state, vm, snackbar)
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = { StoreNavigationBar(state, vm) },
            ) { padding ->
                Surface(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                    when (state.tab) {
                        Tab.Catalog -> CatalogScreen(state, vm)
                        Tab.Updates -> UpdatesScreen(state, vm)
                        Tab.Settings -> SettingsScreen(state, vm)
                    }
                }
            }
        }
    }

    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            title = { Text("Разрешите установку") },
            text = { Text("Один раз включите «Разрешить установку из этого источника» для MThread Store — и дальше приложения будут ставиться в одно нажатие.") },
            confirmButton = {
                TextButton(onClick = { askPermission = false; vm.requestInstallPermission() }) { Text("Открыть настройки") }
            },
            dismissButton = { TextButton(onClick = { askPermission = false }) { Text("Позже") } },
        )
    }
}

@Composable
private fun StoreNavigationBar(state: UiState, vm: StoreViewModel) {
    NavigationBar {
        NavigationBarItem(
            selected = state.tab == Tab.Catalog,
            onClick = { vm.setTab(Tab.Catalog) },
            icon = { Icon(if (state.tab == Tab.Catalog) Icons.Filled.Apps else Icons.Outlined.Apps, null) },
            label = { Text("Каталог") },
        )
        NavigationBarItem(
            selected = state.tab == Tab.Updates,
            onClick = { vm.setTab(Tab.Updates) },
            icon = {
                BadgedBox(badge = { if (state.updates.isNotEmpty()) Badge { Text("${state.updates.size}") } }) {
                    Icon(if (state.tab == Tab.Updates) Icons.Filled.Update else Icons.Outlined.Update, null)
                }
            },
            label = { Text("Обновления") },
        )
        NavigationBarItem(
            selected = state.tab == Tab.Settings,
            onClick = { vm.setTab(Tab.Settings) },
            icon = { Icon(if (state.tab == Tab.Settings) Icons.Filled.Settings else Icons.Outlined.Settings, null) },
            label = { Text("Настройки") },
        )
    }
}

@Composable
fun StoreTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
