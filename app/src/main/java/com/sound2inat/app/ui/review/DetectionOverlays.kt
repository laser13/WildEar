package com.sound2inat.app.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.sound2inat.inference.WindowPrediction

/**
 * Translucent per-detection rectangles overlaid on the spectrogram. Each
 * [WindowPrediction] whose taxon matches a known species row is rendered as a
 * coloured box spanning `[startMs, endMs]` along the X-axis (full canvas
 * height on Y), with the species' palette color and an alpha that bumps from
 * [BASE_ALPHA] to [HIGHLIGHT_ALPHA] when its species is the active highlight.
 *
 * Tap routing: a tap at `(x, y)` selects the *topmost* (most-confident-first)
 * rectangle whose horizontal slot contains `x`; the resolved [WindowPrediction]
 * is forwarded to [onTap]. The ViewModel translates that into a `seekTo` +
 * `highlight` pair (see [ReviewViewModel.onWindowTapped]).
 *
 * The overlay shares the spectrogram's coordinate system — drop it inside the
 * same `Box` as the spectrogram `Image` so `Modifier.fillMaxSize()` matches.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun DetectionOverlays(
    windowPreds: List<WindowPrediction>,
    species: List<SpeciesRow>,
    highlight: Long?,
    durationMs: Long,
    onTap: (WindowPrediction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (durationMs <= 0L || species.isEmpty()) return
    // Sorted-by-confidence index drives the palette mapping; taxon name is a
    // tiebreaker so equal-confidence species don't shuffle colours between
    // recompositions.
    val sortedRows = species.sortedWith(
        compareByDescending<SpeciesRow> { it.maxConfidence }
            .thenBy { it.taxonScientificName },
    )
    val rowByTaxon: Map<String, IndexedRow> = sortedRows
        .mapIndexed { idx, row -> row.taxonScientificName to IndexedRow(row, idx) }
        .toMap()
    // Predictions whose species is in the list, sorted by descending confidence
    // so a tap on overlapping rectangles picks the strongest detection first.
    // Falls back to one synthetic rectangle per species row using
    // [SpeciesRow.firstSeenMs]/[SpeciesRow.lastSeenMs] when no per-window data
    // is available — that's the case for drafts opened after their inference
    // run finished (windows are not persisted in the DB).
    val matched: List<Match> = if (windowPreds.isNotEmpty()) {
        windowPreds
            .mapNotNull { p -> rowByTaxon[p.taxonScientificName]?.let { Match(p, it) } }
            .sortedByDescending { it.prediction.confidence }
    } else {
        sortedRows.map { row ->
            val synthetic = WindowPrediction(
                startMs = row.firstSeenMs,
                endMs = row.lastSeenMs,
                taxonScientificName = row.taxonScientificName,
                taxonCommonName = row.taxonCommonName,
                confidence = row.maxConfidence,
            )
            Match(synthetic, rowByTaxon.getValue(row.taxonScientificName))
        }
    }
    if (matched.isEmpty()) return

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(matched, durationMs) {
                detectTapGestures { offset ->
                    val hit = matched.firstOrNull { m ->
                        val left = (m.prediction.startMs.toFloat() / durationMs) * size.width
                        val right = (m.prediction.endMs.toFloat() / durationMs) * size.width
                        offset.x in left..right
                    }
                    hit?.let { onTap(it.prediction) }
                }
            },
    ) {
        for (m in matched) {
            val left = (m.prediction.startMs.toFloat() / durationMs) * size.width
            val right = (m.prediction.endMs.toFloat() / durationMs) * size.width
            val width = (right - left).coerceAtLeast(MIN_RECT_WIDTH_PX)
            val isActive = highlight == m.indexed.row.detectionId
            val alpha = if (isActive) HIGHLIGHT_ALPHA else BASE_ALPHA
            val baseColor = SpeciesPalette.colorFor(
                taxon = m.indexed.row.taxonScientificName,
                indexHint = m.indexed.index,
            )
            drawRect(
                color = baseColor.copy(alpha = alpha),
                topLeft = Offset(left, 0f),
                size = Size(width = width, height = size.height),
            )
            if (isActive) {
                drawRect(
                    color = Color.White.copy(alpha = HIGHLIGHT_STROKE_ALPHA),
                    topLeft = Offset(left, 0f),
                    size = Size(width = width, height = size.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = HIGHLIGHT_STROKE_WIDTH_PX,
                    ),
                )
            }
        }
    }
}

private data class IndexedRow(val row: SpeciesRow, val index: Int)
private data class Match(val prediction: WindowPrediction, val indexed: IndexedRow)

// 0.35 was visually overwhelming when detections covered most of the clip:
// viridis spectrogram (cool blues/greens) + a translucent species-colored
// rectangle reads as a fully-opaque red/pink band. Drop the base alpha so the
// spectrogram stays the dominant visual; highlights still pop above it.
private const val BASE_ALPHA = 0.15f
private const val HIGHLIGHT_ALPHA = 0.55f
private const val HIGHLIGHT_STROKE_ALPHA = 0.9f
private const val HIGHLIGHT_STROKE_WIDTH_PX = 3f
private const val MIN_RECT_WIDTH_PX = 2f
