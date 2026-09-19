package com.liuxue.assistant.feature.files.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.liuxue.assistant.domain.ExpiryLevel
import com.liuxue.assistant.util.DateUtils

@Composable
fun ExpiryBadge(expireDate: Long?, remindDaysBefore: Int, modifier: Modifier = Modifier) {
    val level = ExpiryLevel.of(expireDate, remindDaysBefore)
    if (expireDate == null) {
        Text(
            text = level.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }
    Text(
        text = level.label + " · " + DateUtils.humanRemaining(expireDate),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(level.color)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
