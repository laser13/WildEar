# SpeciesDetailsSheet — iNat Comments Display

**Date:** 2026-05-08  
**Scope:** UI-only change to `SpeciesDetailsSheet.kt` and `strings.xml`. No API, ViewModel, or data model changes.

## Problem

When an observation has been uploaded to iNaturalist and community members leave comments (corrections, confirmations, identifications), the user currently only sees the comment count ("Comments: 3") in the species details sheet. The actual comment text is fetched from the API (`ObservationDetail.comments: List<ObservationComment>`) but never displayed.

## Current state

- `ObservationDetail.comments` is populated by `INaturalistClient` with up to `MAX_PREVIEW_COMMENTS = 3` comments.
- Each `ObservationComment` has `username: String` and `body: String`.
- `SpeciesDetailsSheet` shows `DetailRow(label="Comments", value="${d.commentsCount}")` — count only.

## Design

Replace the comment count `DetailRow` with a comment list. No other file changes.

### Comment section layout (inside `Loaded` state in `SpeciesDetailsSheet`)

When `d.comments.isNotEmpty()`:

```
Comments                     ← labelMedium, onSurfaceVariant
────────────────────────────
@username1                   ← bodySmall, primary
Looks like Turdus merula…    ← bodyMedium, onSurface
────────────────────────────
@username2                   ← bodySmall, primary
Confirmed! Great find.       ← bodyMedium, onSurface
[ … and 2 more ]             ← bodySmall, onSurfaceVariant (if commentsCount > shown)
```

When `d.comments.isEmpty()` but `d.commentsCount > 0` (API returned no bodies — edge case):

```
Comments: 3                  ← fallback to existing DetailRow behaviour
```

When `d.commentsCount == 0`: section is hidden (as today).

### Overflow indicator

If `d.commentsCount > d.comments.size`, show a trailing line:
```
… and N more
```
where N = `d.commentsCount - d.comments.size`.

### String resources to add

```xml
<string name="sheet_comments_more">… and %1$d more</string>
```

## Files

| File | Change |
|------|--------|
| `app/src/main/java/com/sound2inat/app/ui/review/SpeciesDetailsSheet.kt` | Replace comment count `DetailRow` with comment list composable |
| `app/src/main/res/values/strings.xml` | Add `sheet_comments_more` |

## Non-goals

- No "View on iNat" link (user confirmed not needed).
- No fetching of comments beyond the existing `MAX_PREVIEW_COMMENTS = 3` cap.
- No paging / lazy loading of comments.
- No markdown rendering of comment bodies.
