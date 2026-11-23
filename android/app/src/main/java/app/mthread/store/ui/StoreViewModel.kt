package app.mthread.store.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.mthread.store.StoreApp
import app.mthread.store.data.Catalog
import app.mthread.store.data.CatalogApp
import app.mthread.store.data.HistoryEntry
import app.mthread.store.data.Installed
import app.mthread.store.data.Search
import app.mthread.store.install.AutoUpdate
import app.mthread.store.install.InstallEvents
import app.mthread.store.install.InstallWorker
import app.mthread.store.install.UpdateCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { Queued, Download, Install, Confirm }

data class AppTask(val phase: Phase, val done: Long = 0, val total: Long = 0, val waiting: Boolean = false) {
    val fraction get() = if (total > 0) done.toFloat() / total else null
}

enum class Tab { Catalog, Updates, Settings }
enum class StatusFilter(val label: String) { All("Все"), Installed("Установленные"), Updates("Обновления"), NotInstalled("Не установлены") }
enum class Sort(val label: String) { Name("По названию"), Recent("Недавно обновлённые"), Size("По размеру") }

data class SettingsState(
    val autoUpdate: Boolean = true,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val notifyUpdates: Boolean = true,
    val intervalHours: Int = 6,
    val lastCheck: Long = 0,
    val excluded: Set<String> = emptySet(),
)

data class UiState(
    val catalog: Catalog? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val installed: Map<String, Long> = emptyMap(),
    val tasks: Map<String, AppTask> = emptyMap(),
    val selectedId: String? = null,
    val tab: Tab = Tab.Catalog,
    val query: String = "",
    val category: String? = null,
    val status: StatusFilter = StatusFilter.All,
    val sort: Sort = Sort.Name,
    val settings: SettingsState = SettingsState(),
    val history: List<HistoryEntry> = emptyList(),
    val canInstall: Boolean = true,
    val checking: Boolean = false,
) {
    val apps get() = catalog?.apps.orEmpty().sortedBy { it.title.lowercase() }
    val updates get() = apps.filter { Installed.hasUpdate(it, installed[it.id]) }
    val installedApps get() = apps.filter { it.id in installed }
    val selected get() = apps.firstOrNull { it.id == selectedId }
    val categories get() = apps.map { it.category.trim() }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }

    /** Каталог с учётом поиска, категории, фильтра и сортировки. */
    val visible: List<CatalogApp>
        get() {
            var list = apps.filter { app ->
                (category == null || app.category.trim().equals(category, ignoreCase = true)) &&
                    when (status) {
                        StatusFilter.All -> true
                        StatusFilter.Installed -> app.id in installed
                        StatusFilter.Updates -> Installed.hasUpdate(app, installed[app.id])
                        StatusFilter.NotInstalled -> app.id !in installed
                    }
            }
            if (query.isNotBlank()) return Search.filter(list, query) // при поиске важнее релевантность
            list = when (sort) {
                Sort.Name -> list
                Sort.Recent -> list.sortedByDescending { it.updated.orEmpty() }
                Sort.Size -> list.sortedByDescending { it.size }
            }
            return list
        }
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data object NeedInstallPermission : UiEvent
    data class Launch(val intent: Intent) : UiEvent
}

class StoreViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app as StoreApp
    private val context get() = getApplication<Application>()
    private val _state = MutableStateFlow(UiState(catalog = store.catalog.cached()))
    val state: StateFlow<UiState> = _state
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val seenRunning = mutableSetOf<String>()

    init {
        loadSettings()
        refresh()
        viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfosByTagFlow(InstallWorker.TAG).collect(::onWork)
        }
        viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfosByTagFlow(UpdateCheckWorker.TAG).collect { infos ->
                val running = infos.any { it.state == WorkInfo.State.RUNNING }
                if (_state.value.checking && !running) refresh()
                _state.update { it.copy(checking = running) }
                loadSettings()
            }
        }
        viewModelScope.launch {
            InstallEvents.events.collect { event ->
                refreshInstalled()
                loadSettings()
                if (!event.success && event.message != null) _events.send(UiEvent.Message(event.message))
            }
        }
    }

    fun refresh() {
        if (!store.catalog.configured) {
            _state.update { it.copy(error = "Store собран без настроек. Запустите админку, создайте репозиторий и пересоберите приложение.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = runCatching { store.catalog.fetch() }
            val err = result.exceptionOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    catalog = result.getOrNull() ?: it.catalog,
                    error = when {
                        err == null -> null
                        it.catalog != null -> "Нет связи с каталогом — показана сохранённая версия"
                        else -> "Не удалось загрузить каталог: ${err.message}"
                    },
                )
            }
            refreshInstalled()
            result.getOrNull()?.let { catalog ->
                withContext(Dispatchers.IO) { runCatching { AutoUpdate.run(context, catalog) } }
                loadSettings()
            }
        }
    }

    fun refreshInstalled() {
        val apps = _state.value.catalog?.apps.orEmpty()
        _state.update {
            it.copy(installed = Installed.versions(context, apps), canInstall = context.packageManager.canRequestPackageInstalls())
        }
    }

    private fun loadSettings() {
        val s = store.settings
        _state.update {
            it.copy(
                settings = SettingsState(s.autoUpdate, s.wifiOnly, s.chargingOnly, s.notifyUpdates, s.checkIntervalHours, s.lastCheck, s.excluded),
                history = s.history,
            )
        }
    }

    // ---------- навигация и фильтры ----------

    fun select(id: String?) = _state.update { it.copy(selectedId = id) }
    fun setTab(tab: Tab) = _state.update { it.copy(tab = tab, selectedId = null) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setCategory(c: String?) = _state.update { it.copy(category = c) }
    fun setStatus(s: StatusFilter) = _state.update { it.copy(status = s) }
    fun setSort(s: Sort) = _state.update { it.copy(sort = s) }

    // ---------- настройки ----------

    fun updateSettings(change: app.mthread.store.data.Settings.() -> Unit, reschedule: Boolean = false) {
        store.settings.change()
        if (reschedule) UpdateCheckWorker.schedule(context, replace = true)
        loadSettings()
    }

    fun setAppAutoUpdate(app: CatalogApp, enabled: Boolean) = updateSettings({
        excluded = if (enabled) excluded - app.id else excluded + app.id
    })

    fun checkNow() {
        _state.update { it.copy(checking = true) }
        UpdateCheckWorker.checkNow(context)
    }

    fun requestInstallPermission() {
        _events.trySend(UiEvent.Launch(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))))
    }

    // ---------- действия с приложениями ----------

    fun install(app: CatalogApp) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _events.trySend(UiEvent.NeedInstallPermission)
            return
        }
        _state.update { it.copy(tasks = it.tasks + (app.id to AppTask(Phase.Queued))) }
        InstallWorker.enqueue(context, app)
    }

    fun updateAll() = _state.value.updates.filter { _state.value.tasks[it.id]?.waiting != false }.forEach(::install)

    fun cancel(app: CatalogApp) = InstallWorker.cancel(context, app.id)

    fun open(app: CatalogApp) {
        context.packageManager.getLaunchIntentForPackage(app.id)?.let { _events.trySend(UiEvent.Launch(it)) }
            ?: _events.trySend(UiEvent.Message("У приложения нет экрана запуска"))
    }

    fun uninstall(app: CatalogApp) {
        _events.trySend(UiEvent.Launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.id}"))))
    }

    private suspend fun onWork(infos: List<WorkInfo>) {
        val tasks = mutableMapOf<String, AppTask>()
        for (info in infos) {
            val id = info.tags.firstOrNull { it.startsWith("app:") }?.removePrefix("app:") ?: continue
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    tasks[id] = AppTask(Phase.Queued, waiting = InstallWorker.TAG_AUTO in info.tags)
                WorkInfo.State.RUNNING -> {
                    seenRunning += id
                    val p = info.progress
                    val phase = when (p.getString(InstallWorker.KEY_PHASE)) {
                        InstallWorker.PHASE_DOWNLOAD -> Phase.Download
                        InstallWorker.PHASE_INSTALL -> Phase.Install
                        InstallWorker.PHASE_CONFIRM -> Phase.Confirm
                        else -> Phase.Queued
                    }
                    tasks[id] = AppTask(phase, p.getLong(InstallWorker.KEY_DONE, 0), p.getLong(InstallWorker.KEY_TOTAL, 0))
                }
                WorkInfo.State.FAILED -> if (seenRunning.remove(id)) {
                    info.outputData.getString(InstallWorker.KEY_ERROR)?.let { _events.send(UiEvent.Message(it)) }
                }
                WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> seenRunning.remove(id)
            }
        }
        _state.update { it.copy(tasks = tasks) }
        refreshInstalled()
    }
}
