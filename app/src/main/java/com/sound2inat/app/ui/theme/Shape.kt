package com.sound2inat.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner tokens for Wild Ear. Photo surfaces previously hardcoded
 * 18/20/24/28 dp; those map onto the M3 size buckets below so all
 * cards read from MaterialTheme.shapes.
 *
 * - extraSmall 8 dp  — chips / small controls (M3 default)
 * - small      12 dp — compact controls (M3 default)
 * - medium     18 dp — thumbnails (was RoundedCornerShape(18.dp))
 * - large      20 dp — buttons inside cards (was RoundedCornerShape(20.dp))
 * - extraLarge 28 dp — primary cards/surfaces (was RoundedCornerShape(28.dp))
 *
 * The intermediate 24 dp radius (hero image clip / suggestion cards)
 * has no dedicated M3 bucket, so [cornerLarge24] exposes it explicitly.
 */
val Sound2iNatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 24 dp radius used by hero/suggestion image clips; no M3 bucket maps to it. */
val cornerLarge24 = RoundedCornerShape(24.dp)
