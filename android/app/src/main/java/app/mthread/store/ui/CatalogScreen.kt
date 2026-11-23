package app.mthread.store.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(state: UiState, vm: StoreViewModel) {
    val visible = state.visible
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SearchField(state.query, vm::setQuery)
        FilterRow(state, vm)
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                state.error?.let { error ->
                    item {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                if (state.query.isNotBlank() || state.category != null || state.status != StatusFilter.All) {
                    item {
                        Text("Найдено: ${visible.size}", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
                items(visible, key = { it.id }) { AppListItem(it, state, vm) }
                if (visible.isEmpty() && !state.loading) item { EmptyState(state.apps.isEmpty()) }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Поиск приложений") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Очистить") }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun FilterRow(state: UiState, vm: StoreViewModel) {
    var sortMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Box {
                FilterChip(
                    selected = state.status != StatusFilter.All,
                    onClick = { statusMenu = true },
                    label = { Text(if (state.status == StatusFilter.All) "Статус" else state.status.label) },
                    leadingIcon = if (state.status != StatusFilter.All) {
                        { Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                )
                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                    StatusFilter.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            trailingIcon = { if (s == state.status) Icon(Icons.Default.Check, null) },
                            onClick = { vm.setStatus(s); statusMenu = false },
                        )
                    }
                }
            }
        }
        item {
            Box {
                FilterChip(
                    selected = false,
                    onClick = { sortMenu = true },
                    label = { Text(state.sort.label) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    enabled = state.query.isBlank(),
                )
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    Sort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            trailingIcon = { if (s == state.sort) Icon(Icons.Default.Check, null) },
                            onClick = { vm.setSort(s); sortMenu = false },
                        )
                    }
                }
            }
        }
        items(state.categories) { c ->
            val selected = state.category.equals(c, ignoreCase = true)
            FilterChip(
                selected = selected,
                onClick = { vm.setCategory(if (selected) null else c) },
                label = { Text(c) },
            )
        }
    }
}

@Composable
private fun EmptyState(catalogEmpty: Boolean) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (catalogEmpty) "Каталог пока пуст" else "Ничего не нашлось",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (!catalogEmpty) {
            Text("Попробуйте другой запрос или сбросьте фильтры", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
