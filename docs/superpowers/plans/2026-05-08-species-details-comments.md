# SpeciesDetailsSheet — iNat Comments Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the text of iNat comments inside `SpeciesDetailsSheet` instead of just a count.

**Architecture:** Pure UI change. `ObservationDetail.comments: List<ObservationComment>` is already fetched by `INaturalistClient` (up to 3 items, `MAX_PREVIEW_COMMENTS = 3`). We replace the existing `DetailRow(label="Comments", value=count)` with a comment list rendered via `items()` inside the existing `LazyColumn`. No ViewModel, API, or Room changes.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, `LazyColumn` / `items()`.

---

## File Structure

| File | Action |
|------|--------|
| `app/src/main/res/values/strings.xml` | Add `sheet_comments_more` string |
| `app/src/main/java/com/sound2inat/app/ui/review/SpeciesDetailsSheet.kt` | Replace comment-count `DetailRow` with comment list; add `Column` import |

---

### Task 1: Add string resource and update SpeciesDetailsSheet

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/SpeciesDetailsSheet.kt`

> No unit test step — this is a pure UI layout change with no extractable logic. Manual verification is the acceptance criterion (Step 4).

- [ ] **Step 1: Add string to `strings.xml`**

In the `<!-- Species details sheet -->` section (after `sheet_label_comments`), add:

```xml
<string name="sheet_comments_more">… and %1$d more</string>
```

- [ ] **Step 2: Add `Column` import to `SpeciesDetailsSheet.kt`**

The file already imports `Row` and `Spacer` from layout. Add `Column` to the same group:

```kotlin
import androidx.compose.foundation.layout.Column
```

- [ ] **Step 3: Replace the comment-count block in `SpeciesDetailsSheet.kt`**

Locate and replace the existing block (inside the `is ObservationDetailLoadState.Loaded ->` branch of the `LazyColumn`):

**Remove:**
```kotlin
if (d.commentsCount > 0) {
    item {
        DetailRow(
            label = stringResource(R.string.sheet_label_comments),
            value = "${d.commentsCount}",
        )
    }
}
```

**Replace with:**
```kotlin
if (d.commentsCount > 0) {
    item {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sheet_label_comments),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }
    if (d.comments.isNotEmpty()) {
        items(d.comments) { comment ->
            HorizontalDivider()
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(
                    text = "@${comment.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = comment.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (d.commentsCount > d.comments.size) {
            item {
                Text(
                    text = stringResource(
                        R.string.sheet_comments_more,
                        d.commentsCount - d.comments.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    } else {
        // Edge case: API returned commentsCount > 0 but no comment bodies
        item {
            DetailRow(
                label = stringResource(R.string.sheet_label_comments),
                value = "${d.commentsCount}",
            )
        }
    }
}
```

- [ ] **Step 4: Manual verification**

Build and run the app. Open a draft that has been uploaded to iNat:
- Tap a species that has iNat comments → sheet opens → iNat section shows comments as `@username` + body text
- If the observation has more than 3 comments, "… and N more" appears at the bottom
- If the observation has 0 comments, the comments section is hidden
- If comments were not loaded yet, the section shows a spinner (existing Loading state handles this)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/java/com/sound2inat/app/ui/review/SpeciesDetailsSheet.kt
git commit -m "feat(review): show iNat comment text in species details sheet"
```

---

## Self-review

- **Spec coverage:** spec requires comment list, overflow indicator, fallback to count, hidden when 0 — all covered.
- **No placeholders:** all code is complete.
- **Type consistency:** `ObservationComment.username` / `.body` match `INaturalistClient.kt:741-744`. `d.commentsCount` and `d.comments` match `ObservationDetail` fields. `R.string.sheet_comments_more` is added in Step 1.
