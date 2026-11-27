package com.minifile.explorer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.gallery.GalleryGroupBy
import com.minifile.explorer.core.gallery.GridDensity
import com.minifile.explorer.core.gallery.MediaExportFormat
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.ui.i18n.LocalAppStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryGroupSheet(
    currentGroupBy: GalleryGroupBy,
    currentDensity: GridDensity,
    onSelectGroupBy: (GalleryGroupBy) -> Unit,
    onSelectDensity: (GridDensity) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = s[Str.GALLERY_GROUP_BY],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))

            val groupOptions = listOf(
                GalleryGroupBy.DAY to s[Str.GALLERY_GROUP_DAY],
                GalleryGroupBy.MONTH to s[Str.GALLERY_GROUP_MONTH],
                GalleryGroupBy.YEAR to s[Str.GALLERY_GROUP_YEAR],
                GalleryGroupBy.PLACE to s[Str.GALLERY_GROUP_PLACE],
                GalleryGroupBy.FOLDER to s[Str.GALLERY_GROUP_FOLDER],
            )
            for ((option, label) in groupOptions) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectGroupBy(option) }
                        .padding(vertical = 6.dp),
                ) {
                    RadioButton(
                        selected = currentGroupBy == option,
                        onClick = { onSelectGroupBy(option) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                text = s[Str.GALLERY_DENSITY],
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            val densityOptions = listOf(
                GridDensity.LARGE to s[Str.GALLERY_DENSITY_LARGE],
                GridDensity.MEDIUM to s[Str.GALLERY_DENSITY_MEDIUM],
                GridDensity.SMALL to s[Str.GALLERY_DENSITY_SMALL],
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                densityOptions.forEachIndexed { idx, (density, label) ->
                    SegmentedButton(
                        selected = currentDensity == density,
                        onClick = { onSelectDensity(density) },
                        shape = SegmentedButtonDefaults.itemShape(idx, densityOptions.size),
                    ) {
                        Text(label)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaExportOptionsSheet(
    format: MediaExportFormat,
    removeLocation: Boolean,
    onSelectFormat: (MediaExportFormat) -> Unit,
    onToggleRemoveLocation: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = s[Str.GALLERY_EXPORT_OPTIONS],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            Text(
                text = s[Str.GALLERY_EXPORT_FORMAT],
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = format == MediaExportFormat.ORIGINAL,
                    onClick = { onSelectFormat(MediaExportFormat.ORIGINAL) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) {
                    Text(s[Str.GALLERY_FORMAT_ORIGINAL])
                }
                SegmentedButton(
                    selected = format == MediaExportFormat.MOST_COMPATIBLE,
                    onClick = { onSelectFormat(MediaExportFormat.MOST_COMPATIBLE) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) {
                    Text(s[Str.GALLERY_FORMAT_COMPATIBLE])
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleRemoveLocation(!removeLocation) }
                    .padding(vertical = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s[Str.GALLERY_REMOVE_LOCATION],
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = s[Str.SETTINGS_REMOVE_LOCATION_DEFAULT_DESC],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = removeLocation,
                    onCheckedChange = onToggleRemoveLocation,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
