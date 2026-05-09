# Home Screen UI Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Улучшить главный экран: коллапсирующий заголовок с логотипом и sticky date headers.

**Architecture:** Scaffold.topBar заменяется на кастомный компактный бар с логотипом + чипами фильтров + иконкой настроек. Большой логотип + заголовок добавляется как первый item в LazyColumn — уезжает при скролле. Date headers переводятся в `stickyHeader {}`.

**Tech Stack:** Jetpack Compose, Material 3, `@OptIn(ExperimentalFoundationApi::class)` для `stickyHeader`

---

## Текущее состояние

`HomeScreen.kt` (`665` строк):

- `Scaffold.topBar` → `TopAppBar` с текстом «WildEar» и иконкой Settings
- `RecordingFilterBar` — отдельный компонент ниже TopAppBar, внутри `Column` над `LazyColumn`. Состоит из двух рядов:
  - Ряд 1: чипы фильтров (`FilterChip` x2)
  - Ряд 2 (условный): кнопки bulk-delete (Select all / Delete N)
- `LazyColumn` с `item {}` для date-заголовков (не sticky)

## Целевое состояние

```
┌─────────────────────────────────────────┐
│ [logo 32dp] [chip] [chip]     [Settings] │  ← Scaffold.topBar (всегда)
├─────────────────────────────────────────┤
│  [Select all]         [Delete (N)]       │  ← BulkActionsRow (условно, ниже topBar)
├─────────────────────────────────────────┤
│  [большой логотип 72dp]                  │  ← LazyColumn item[0] (скроллится)
│  WildEar                                 │
│ ─────────────────────────────────────── │
│  Today                                   │  ← stickyHeader (прилипает к topBar)
│  [Card] [Card]                           │
│  Yesterday                               │  ← stickyHeader
│  [Card]                                  │
└─────────────────────────────────────────┘
```

## Изменения файлов

- **Modify:** `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt`

Никакие другие файлы не затрагиваются.

---

## Детальный дизайн

### Compact TopBar

```kotlin
@Composable
private fun HomeTopBar(
    filterMode: FilterMode,
    onFilterChange: (FilterMode) -> Unit,
    onSettings: () -> Unit,
    showFilterChips: Boolean,   // false когда enrichedDrafts.isEmpty()
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Маленький логотип приложения
        Image(
            painter = painterResource(R.mipmap.ic_launcher_round),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(32.dp)
                .clip(CircleShape),
        )
        // Чипы фильтров занимают оставшееся место
        if (showFilterChips) {
            FilterChipsRow(
                filterMode = filterMode,
                onFilterChange = onFilterChange,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        // Кнопка настроек
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.cd_settings))
        }
    }
}
```

`FilterChipsRow` — новый приватный компонент, содержит только чипы (без bulk-delete логики):

```kotlin
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
}
```

### BulkActionsRow

Bulk-delete строка (Select all / Delete N) переносится из `RecordingFilterBar` в отдельный `BulkActionsRow`. Располагается В КОНТЕНТЕ Scaffold (не в topBar), показывается условно когда `filterMode == NOTHING_DETECTED && totalVisible > 0`:

```kotlin
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
            Text(if (allSelected) stringResource(R.string.filter_deselect_all) else stringResource(R.string.filter_select_all))
        }
        Spacer(modifier = Modifier.weight(1f))
        if (selectedCount > 0) {
            TextButton(
                onClick = onDeleteSelected,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.filter_delete_selected, selectedCount))
            }
        }
    }
}
```

### Scrollable Large Header

Добавляется как первый `item {}` в LazyColumn, только когда `enrichedDrafts.isNotEmpty()`:

```kotlin
item(key = "large_header") {
    LargeHeader()
}
```

```kotlin
@Composable
private fun LargeHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.Start,
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

### Sticky Date Headers

Меняем `item(key = "header_${group.label}") { ... }` на `stickyHeader(key = "header_${group.label}") { ... }`.

Sticky header должен иметь фоновый цвет совпадающий с `Scaffold` (иначе карточки будут просвечивать снизу):

```kotlin
stickyHeader(key = "header_${group.label}") {
    Text(
        group.label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 0.dp, top = 8.dp, bottom = 4.dp),
    )
}
```

### Перестройка HomeScreen

Старый `RecordingFilterBar` удаляется. `HomeScreen` перестраивается:

```kotlin
Scaffold(
    topBar = {
        HomeTopBar(
            filterMode = filterMode,
            onFilterChange = { vm.setFilterMode(it) },
            onSettings = onSettings,
            showFilterChips = enrichedDrafts.isNotEmpty(),
        )
    },
    floatingActionButton = { /* без изменений */ },
) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        // Model not installed banner (без изменений)
        if (!state.isModelReady) { /* ... */ }

        // Bulk delete row (условно)
        if (filterMode == FilterMode.NOTHING_DETECTED && filteredDrafts.isNotEmpty()) {
            BulkActionsRow(
                selectedCount = selectedIds.size,
                totalVisible = filteredDrafts.size,
                onSelectAll = { vm.selectAllVisible() },
                onClearSelection = { vm.clearSelection() },
                onDeleteSelected = { bulkDeletePreview = vm.previewSelectedDelete() },
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                enrichedDrafts.isEmpty() -> EmptyState(modifier = Modifier.fillMaxSize())
                filteredDrafts.isEmpty() -> { /* "No recordings match this filter" */ }
                else -> {
                    val groups = remember(filteredDrafts) { groupDraftsByDate(filteredDrafts) }
                    LazyColumn(/* ... */) {
                        item(key = "large_header") { LargeHeader() }
                        groups.forEach { group ->
                            stickyHeader(key = "header_${group.label}") { /* ... */ }
                            items(group.drafts, key = { it.id }) { /* ... */ }
                        }
                    }
                }
            }
        }
    }
}
```

## Граничные случаи

- **Пустой список:** `LargeHeader` не показывается (нет LazyColumn). TopBar показывает лого + настройки, без чипов.
- **Фильтр активен, список пуст:** `filteredDrafts.isEmpty()` — нет LazyColumn, нет `LargeHeader`.
- **Режим bulk-delete:** `BulkActionsRow` показывается над списком, занимает отдельную строку.
- **`@OptIn(ExperimentalFoundationApi::class)`:** нужна для `stickyHeader {}` — добавить на `HomeScreen` и на приватные функции LazyListScope.

## Что НЕ меняется

- `HomeViewModel.kt` — не трогается
- `strings.xml` — новых строк не нужно
- Вся логика фильтрации, удаления, выбора — без изменений
- `RecordingCard`, `RecordingThumbnail`, все диалоги — без изменений
