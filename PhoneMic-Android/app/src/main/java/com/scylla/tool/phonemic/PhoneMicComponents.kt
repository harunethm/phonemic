package com.scylla.tool.phonemic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scylla.tool.phonemic.ui.theme.PhoneMicInkFaint
import com.scylla.tool.phonemic.ui.theme.PlexMonoFamily

/** Small tinted status pill with a leading color dot — used for pairing/quality state. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.18f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
            Text(
                text = text,
                color = color,
                fontFamily = PlexMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp
            )
        }
    }
}

/** Fully-rounded pill button: solid for primary/destructive actions, outlined for ghost/secondary. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    ghost: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    icon: (@Composable () -> Unit)? = null
) {
    val resolvedContainer = if (containerColor != Color.Unspecified) {
        containerColor
    } else if (ghost) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.primary
    }
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            icon()
            Spacer(modifier = Modifier.size(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = fontSize)
    }
    if (ghost) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = resolvedContainer, contentColor = contentColor),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            contentPadding = contentPadding,
            modifier = modifier,
            content = content
        )
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = resolvedContainer, contentColor = contentColor),
            contentPadding = contentPadding,
            modifier = modifier,
            content = content
        )
    }
}

/** Custom toggle track (44x24, 20x20 thumb) replacing the stock Material Switch look. */
@Composable
fun SwitchTrack(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val trackColor = if (checked) {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val borderColor = if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
    val thumbColor = if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    val trackShape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(trackShape)
            .background(trackColor, trackShape)
            .border(1.dp, borderColor, trackShape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(thumbColor, CircleShape)
        )
    }
}

/** Full-width list row: bold label + helper caption on the left, a [SwitchTrack] on the right. */
@Composable
fun ToggleRow(
    label: String,
    helper: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                helper,
                fontSize = 12.sp,
                color = PhoneMicInkFaint,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        SwitchTrack(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Single connected pill-shaped track with a sliding active segment, replacing a loose FilterChip row. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val trackShape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.surface, trackShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, trackShape)
            .padding(4.dp)
    ) {
        val segmentShape = RoundedCornerShape(999.dp)
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(segmentShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        segmentShape
                    )
                    .clickable(enabled = enabled) { onSelected(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
