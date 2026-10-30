package com.minifile.explorer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.ui.BreadcrumbCrumb

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BreadcrumbBar(
    crumbs: List<BreadcrumbCrumb>,
    onHomeClick: () -> Unit,
    onCrumbClick: (VfsPath) -> Unit,
    onCrumbLongPress: (VfsPath) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val fullPathLabel = crumbs.joinToString(" › ") { it.label }

    LaunchedEffect(crumbs.size, crumbs.lastOrNull()?.path) {
        if (crumbs.isNotEmpty()) {
            listState.animateScrollToItem((crumbs.size * 2 - 1).coerceAtLeast(0))
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = fullPathLabel },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            IconButton(
                onClick = onHomeClick,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "Storage Home",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            LazyRow(
                state = listState,
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(crumbs) { index, crumb ->
                    if (index > 0) {
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    val isLast = index == crumbs.lastIndex
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isLast) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = { crumb.path?.let(onCrumbClick) },
                                onLongClick = { crumb.path?.let(onCrumbLongPress) },
                            ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        ) {
                            Text(
                                text = crumb.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isLast) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
