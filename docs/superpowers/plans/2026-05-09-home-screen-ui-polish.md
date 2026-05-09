# Home Screen UI Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить коллапсирующий заголовок с логотипом, компактный topBar с чипами фильтров, и sticky date headers на главном экране.

**Architecture:** Единственный изменяемый файл — `HomeScreen.kt`. `RecordingFilterBar` разбивается на `FilterChipsRow` (переходит в Scaffold.topBar) и `BulkActionsRow` (остаётся в контенте). Добавляется `LargeHeader` как первый LazyColumn item. Date headers становятся `stickyHeader {}`.

**Tech Stack:** Jetpack Compose, Material 3, `@OptIn(ExperimentalFoundationApi::class)` для `stickyHeader`

---

## Структура изменений

- **Modify only:** `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt`

Текущая структура файла (665 строк):
- Строки 76–240: `HomeScreen()` — основной composable
- Строки 130–139: `Scaffold { topBar = TopAppBar { "WildEar" + Settings } }`
- Строки 170–180: вызов `RecordingFilterBar`
- Строки 202–234: `LazyColumn` с `item {}` для date headers
- Строки 242–268: `EmptyState`
- Строки 528–596: `RecordingFilterBar` composable (удаляется в Task 2)
- Строки 598–665: `BulkDeleteDialog`, `SingleDeleteDialog`

---

## Task 1: Добавить новые composable-компоненты (без изменения HomeScreen)

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt`

В этой таске добавляем четыре новых приватных компонента в конец файла (перед строкой с `BulkDeleteDialog`).
После Task 1 файл компилируется, HomeScreen не меняется — новые composable просто существуют.

- [ ] **Step 1: Добавить импорты**

В начало файла после существующих `import` строк добавить:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
```

`ExperimentalFoundationApi` уже импортирован (строка 3). `ButtonDefaults` будем использовать по полному имени (так уже делается в файле).

- [ ] **Step 2: Добавить FilterChipsRow**

Добавить перед строкой `@Suppress("FunctionNaming")` перед `BulkDeleteDialog` (строка ~598):

```kotlin
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(
    filterMode: FilterMode,
    onFilterChange: (FilterMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filterMode == FilterMode.UPLOADED,
            onClick = {
                onFilterChange(if (filterMode == FilterMode.UPLOADED) FilterMode.ALL else FilterMode.UPLOADED)
            },
            label = {
                Icon(
                    Icons.Filled.Eco,
                    contentDescription = stringResource(R.string.cd_filter_uploaded),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        FilterChip(
            selected = filterMode == FilterMode.NOTHING_DETECTED,
            onClick = {
                onFilterChange(
                    if (filterMode == FilterMode.NOTHING_DETECTED) FilterMode.ALL else FilterMode.NOTHING_DETECTED,
                )
            },
            label = {
                Icon(
                    Icons.Filled.SearchOff,
                    contentDescription = stringResource(R.string.cd_filter_nothing_detected),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}
```

- [ ] **Step 3: Добавить BulkActionsRow**

Добавить сразу после `FilterChipsRow`:

```kotlin
@Suppress("FunctionNaming")
@Composable
private fun BulkActionsRow(
    selectedCount: Int,
    totalVisible: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val allSelected = selectedCount > 0 && selectedCount == totalVisible
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = if (allSelected) onClearSelection else onSelectAll) {
            Text(
                if (allSelected) stringResource(R.string.filter_deselect_all)
                else stringResource(R.string.filter_select_all),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (selectedCount > 0) {
            TextButton(
                onClick = onDeleteSelected,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.filter_delete_selected, selectedCount))
            }
        }
    }
}
```

- [ ] **Step 4: Добавить HomeTopBar**

Добавить после `BulkActionsRow`:

```kotlin
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    filterMode: FilterMode,
    onFilterChange: (FilterMode) -> Unit,
    onSettings: () -> Unit,
    showFilterChips: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_round),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(32.dp)
                .clip(CircleShape),
        )
        if (showFilterChips) {
            FilterChipsRow(
                filterMode = filterMode,
                onFilterChange = onFilterChange,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.cd_settings),
            )
        }
    }
}
```

- [ ] **Step 5: Добавить LargeHeader**

Добавить после `HomeTopBar`:

```kotlin
@Suppress("FunctionNaming")
@Composable
private fun LargeHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_round),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape),
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
```

- [ ] **Step 6: Собрать проект**

```bash
./gradlew :app:assembleDebug
```

Ожидаем: `BUILD SUCCESSFUL`. Новые composable добавлены, ни на что не влияют пока.

- [ ] **Step 7: Коммит**

```bash
git add app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt
git commit -m "feat(home): add FilterChipsRow, BulkActionsRow, HomeTopBar, LargeHeader composables"
```

---

## Task 2: Переключить HomeScreen на новые компоненты + sticky headers

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt`

В этой таске:
1. Заменяем `TopAppBar` на `HomeTopBar` в `Scaffold.topBar`
2. Убираем `RecordingFilterBar` из контента, добавляем `BulkActionsRow` 
3. Добавляем `LargeHeader` как первый item в LazyColumn
4. Конвертируем date headers: `item {}` → `stickyHeader {}`
5. Удаляем `RecordingFilterBar` composable

- [ ] **Step 1: Добавить ExperimentalFoundationApi к @OptIn на HomeScreen**

Текущий код (строка 77):
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
```

Заменить на:
```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
```

- [ ] **Step 2: Заменить Scaffold.topBar**

Текущий код (строки 131–139):
```kotlin
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
            )
        },
```

Заменить на:
```kotlin
        topBar = {
            HomeTopBar(
                filterMode = filterMode,
                onFilterChange = { vm.setFilterMode(it) },
                onSettings = onSettings,
                showFilterChips = enrichedDrafts.isNotEmpty(),
            )
        },
```

- [ ] **Step 3: Убрать RecordingFilterBar, добавить BulkActionsRow**

Текущий код (строки 170–180):
```kotlin
            if (enrichedDrafts.isNotEmpty()) {
                RecordingFilterBar(
                    filterMode = filterMode,
                    onFilterChange = { vm.setFilterMode(it) },
                    selectedCount = selectedIds.size,
                    totalVisible = filteredDrafts.size,
                    onSelectAll = { vm.selectAllVisible() },
                    onClearSelection = { vm.clearSelection() },
                    onDeleteSelected = { bulkDeletePreview = vm.previewSelectedDelete() },
                )
            }
```

Заменить на:
```kotlin
            if (filterMode == FilterMode.NOTHING_DETECTED && filteredDrafts.isNotEmpty()) {
                BulkActionsRow(
                    selectedCount = selectedIds.size,
                    totalVisible = filteredDrafts.size,
                    onSelectAll = { vm.selectAllVisible() },
                    onClearSelection = { vm.clearSelection() },
                    onDeleteSelected = { bulkDeletePreview = vm.previewSelectedDelete() },
                )
            }
```

- [ ] **Step 4: Добавить LargeHeader + конвертировать date headers в stickyHeader**

Текущий код `LazyColumn` блока (строки 204–234):
```kotlin
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            groups.forEach { group ->
                                item(key = "header_${group.label}") {
                                    Text(
                                        group.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                items(group.drafts, key = { it.id }) { d ->
                                    RecordingCard(
                                        summary = d,
                                        observeTopLabel = vm::observeTopLabel,
                                        observeTopSpecies = vm::observeTopSpecies,
                                        observeDetectionCount = vm::observeDetectionCount,
                                        observeInatObservationCount = vm::observeInatObservationCount,
                                        observeTaxonPhoto = vm::observeTaxonPhoto,
                                        selectionMode = selectionMode,
                                        selected = d.id in selectedIds,
                                        onToggleSelection = { vm.toggleSelection(d.id) },
                                        onClick = { onOpenDraft(d.id) },
                                        onLongClick = { longPressedDraft = d },
                                    )
                                }
                            }
                        }
```

Заменить на:
```kotlin
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "large_header") {
                                LargeHeader()
                            }
                            groups.forEach { group ->
                                stickyHeader(key = "header_${group.label}") {
                                    Text(
                                        group.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.background)
                                            .padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                items(group.drafts, key = { it.id }) { d ->
                                    RecordingCard(
                                        summary = d,
                                        observeTopLabel = vm::observeTopLabel,
                                        observeTopSpecies = vm::observeTopSpecies,
                                        observeDetectionCount = vm::observeDetectionCount,
                                        observeInatObservationCount = vm::observeInatObservationCount,
                                        observeTaxonPhoto = vm::observeTaxonPhoto,
                                        selectionMode = selectionMode,
                                        selected = d.id in selectedIds,
                                        onToggleSelection = { vm.toggleSelection(d.id) },
                                        onClick = { onOpenDraft(d.id) },
                                        onLongClick = { longPressedDraft = d },
                                    )
                                }
                            }
                        }
```

Изменения:
- `contentPadding` убирает `vertical = 8.dp` (сверху не нужен отступ — есть `LargeHeader`)
- `item(key = "large_header") { LargeHeader() }` — первым элементом
- `stickyHeader(key = ...)` вместо `item(key = ...)`
- Sticky header получает `Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)` — нужно чтобы карточки не просвечивали под заголовком при скролле

- [ ] **Step 5: Удалить RecordingFilterBar composable**

Найти и полностью удалить блок (строки 528–596):

```kotlin
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingFilterBar(
    filterMode: FilterMode,
    onFilterChange: (FilterMode) -> Unit,
    selectedCount: Int,
    totalVisible: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val allSelected = filterMode == FilterMode.NOTHING_DETECTED &&
        selectedCount > 0 && selectedCount == totalVisible
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = filterMode == FilterMode.UPLOADED,
                onClick = {
                    onFilterChange(if (filterMode == FilterMode.UPLOADED) FilterMode.ALL else FilterMode.UPLOADED)
                },
                label = {
                    Icon(Icons.Filled.Eco, contentDescription = stringResource(R.string.cd_filter_uploaded), modifier = Modifier.size(18.dp))
                },
            )
            FilterChip(
                selected = filterMode == FilterMode.NOTHING_DETECTED,
                onClick = {
                    onFilterChange(
                        if (filterMode == FilterMode.NOTHING_DETECTED) FilterMode.ALL else FilterMode.NOTHING_DETECTED,
                    )
                },
                label = {
                    Icon(Icons.Filled.SearchOff, contentDescription = stringResource(R.string.cd_filter_nothing_detected), modifier = Modifier.size(18.dp))
                },
            )
        }
        if (filterMode == FilterMode.NOTHING_DETECTED && totalVisible > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = if (allSelected) onClearSelection else onSelectAll) {
                    Text(if (allSelected) stringResource(R.string.filter_deselect_all) else stringResource(R.string.filter_select_all))
                }
                Spacer(modifier = Modifier.weight(1f))
                if (selectedCount > 0) {
                    TextButton(
                        onClick = onDeleteSelected,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.filter_delete_selected, selectedCount))
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Удалить неиспользуемые импорты**

После удаления `RecordingFilterBar` и `TopAppBar` убрать неиспользуемые импорты:

```kotlin
import androidx.compose.material3.TopAppBar   // удалить
```

Также удалить `import androidx.compose.material.icons.filled.Delete` если нигде в файле не используется иконка `Icons.Filled.Delete` (проверить grep-ом):

```bash
grep -n "Icons.Filled.Delete" app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt
```

Если строк нет — удалить импорт строки 22.

- [ ] **Step 7: Собрать проект**

```bash
./gradlew :app:assembleDebug
```

Ожидаем: `BUILD SUCCESSFUL`.

Если ошибка компиляции про `Image` — добавить альтернативный импорт:
```kotlin
// Если androidx.compose.foundation.Image не найден, заменить на:
import androidx.compose.foundation.layout.Box
// и заменить все вызовы Image(...) на AsyncImage с model = R.mipmap.ic_launcher_round
```

Если ошибка `Unresolved reference: stickyHeader` — убедиться что `@OptIn(ExperimentalFoundationApi::class)` добавлен на `HomeScreen` (Step 1 этой таски).

- [ ] **Step 8: Коммит**

```bash
git add app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt
git commit -m "feat(home): collapsing header with logo, compact topBar, sticky date headers"
```

---

## Самопроверка после завершения

Запустить приложение и проверить:

1. Главный экран открывается — вверху компактная полоска: маленький логотип слева, чипы фильтров, иконка настроек справа.
2. Список записей показывает большой логотип + «WildEar» вверху.
3. При скролле вверх большой заголовок уезжает — остаётся только компактная полоска.
4. Метки дат («Today», «Yesterday», ...) прилипают к верху при скролле и меняются когда пересекаешь границу следующей группы.
5. Пустой список (нет записей) — виден компактный topBar без чипов, только логотип + настройки.
6. Режим bulk-delete: фильтр SearchOff выбран → появляется строка «Select all / Delete (N)» между topBar и списком.
7. Чипы фильтров работают (Eco = только загруженные, SearchOff = только без детекций).
