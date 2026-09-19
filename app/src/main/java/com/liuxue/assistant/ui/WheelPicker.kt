package com.liuxue.assistant.ui

import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Calendar

/** 一个滚轮（年 / 月 / 日 / 天数…） */
@Composable
private fun Wheel(range: IntRange, value: Int, onChange: (Int) -> Unit) {
    AndroidView(
        factory = { ctx ->
            NumberPicker(ctx).apply {
                minValue = range.first
                maxValue = range.last
                wrapSelectorWheel = true
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { np ->
            if (np.minValue != range.first) np.minValue = range.first
            if (np.maxValue != range.last) np.maxValue = range.last
            if (np.value != value) np.value = value.coerceIn(range.first, range.last)
            np.setOnValueChangedListener { _, _, v -> onChange(v) }
        },
        modifier = Modifier.width(82.dp)
    )
}

private fun daysInMonth(year: Int, month: Int): Int =
    Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, 1)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)

/** 日期滑轮：年 / 月 / 日，可选任意日期（默认落在 9:00） */
@Composable
fun WheelDateDialog(
    title: String,
    initialMillis: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    val cal = remember { Calendar.getInstance().apply { timeInMillis = initialMillis } }
    var year by remember { mutableStateOf(cal.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(cal.get(Calendar.MONTH) + 1) }
    var day by remember { mutableStateOf(cal.get(Calendar.DAY_OF_MONTH)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Wheel(2000..2100, year) { year = it }
                Wheel(1..12, month) { month = it }
                Wheel(1..31, day) { day = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month - 1)
                    set(Calendar.DAY_OF_MONTH, day.coerceAtMost(daysInMonth(year, month)))
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                onPick(c.timeInMillis)
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 数字滑轮（比如「提前几天提醒」，0 = 不提醒） */
@Composable
fun WheelNumberDialog(
    title: String,
    range: IntRange,
    initial: Int,
    unit: String = "",
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    var value by remember { mutableStateOf(initial.coerceIn(range.first, range.last)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Wheel(range, value) { value = it }
                if (unit.isNotBlank()) Text("  $unit")
            }
        },
        confirmButton = { TextButton(onClick = { onPick(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
