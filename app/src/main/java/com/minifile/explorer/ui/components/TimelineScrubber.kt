package com.minifile.explorer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.gallery.LayoutIndex
import com.minifile.explorer.core.gallery.TimelineLayout
import com.minifile.explorer.core.gallery.TimelineSection
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drift-free vertical timeline scrubber backed by [TimelineLayout] O(log S) section lookup.
 * Appears only when `totalItems > 60` (spec §4.4).
 */
@Composable
fun TimelineScrubber(
    gridState: LazyGridState,
    layoutIndex: LayoutIndex,
    sections: List<TimelineSection>,
    sectionLabelProvider: (TimelineSection) -> String,
    totalItemCount: Int,
    onScrubbingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalItemCount <= 60 || layoutIndex.sections.isEmpty()) return

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var trackHeightPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastHapticSection by remember { mutableIntStateOf(-1) }

    val thumbHeightPx = with(density) { 44.dp.toPx() }
    val scrollFraction by remember(gridState, layoutIndex, trackHeightPx) {
        derivedStateOf {
            TimelineLayout.fractionForFlatIndex(
                index = layoutIndex,
                firstVisibleFlatIndex = gridState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = gridState.firstVisibleItemScrollOffset,
                viewportHeightPx = trackHeightPx.toFloat(),
            )
        }
    }

    val activeFraction = if (isDragging) dragFraction else scrollFraction
    val currentTarget = remember(layoutIndex, activeFraction) {
        TimelineLayout.lookupFraction(layoutIndex, activeFraction)
    }
    val bubbleLabel = sections.getOrNull(currentTarget.sectionIndex)?.let(sectionLabelProvider).orEmpty()

    val maxThumbY = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
    val thumbOffsetY = (activeFraction * maxThumbY).roundToInt()

    Box(
        contentAlignment = Alignment.TopEnd,
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .onSizeChanged { trackHeightPx = it.height.coerceAtLeast(1) }
            .semantics {
                contentDescription = bubbleLabel
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .offset { IntOffset(0, thumbOffsetY) }
                .padding(end = 4.dp),
        ) {
            if (isDragging && bubbleLabel.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = bubbleLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            // Touch target wrapper (32dp wide) around sleek 6dp M3 thumb pill
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier
                    .width(28.dp)
                    .height(44.dp)
                    .pointerInput(layoutIndex, trackHeightPx) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                isDragging = true
                                dragFraction = scrollFraction
                                onScrubbingChanged(true)
                            },
                            onDragEnd = {
                                isDragging = false
                                lastHapticSection = -1
                                onScrubbingChanged(false)
                            },
                            onDragCancel = {
                                isDragging = false
                                lastHapticSection = -1
                                onScrubbingChanged(false)
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val deltaFrac = dragAmount / maxThumbY
                                dragFraction = (dragFraction + deltaFrac).coerceIn(0f, 1f)
                                val target = TimelineLayout.lookupFraction(layoutIndex, dragFraction)
                                if (target.sectionIndex != lastHapticSection) {
                                    lastHapticSection = target.sectionIndex
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                scope.launch {
                                    gridState.scrollToItem(target.flatIndex, target.pixelOffsetPx)
                                }
                            },
                        )
                    },
            ) {
                Box(
                    modifier = Modifier
                        .width(if (isDragging) 8.dp else 5.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isDragging) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            },
                        ),
                )
            }
        }
    }
}
