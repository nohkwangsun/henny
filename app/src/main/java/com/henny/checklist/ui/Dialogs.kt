package com.henny.checklist.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.henny.checklist.data.minuteToText

private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

@Composable
fun DayPicker(
    selected: List<Int>,
    modifier: Modifier = Modifier,
    onChange: (List<Int>) -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        (1..7).forEach { day ->
            val on = day in selected
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable {
                        val next = if (on) selected - day else selected + day
                        onChange(next.sorted())
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = DAY_NAMES[day - 1],
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 시각 선택은 안드로이드 기본 다이얼로그를 그대로 쓴다. 익숙하고 가볍다. */
@Composable
fun TimeField(
    label: String,
    minute: Int?,
    modifier: Modifier = Modifier,
    allowClear: Boolean = true,
    onChange: (Int?) -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                val start = minute ?: (17 * 60)
                TimePickerDialog(
                    context,
                    { _, h, m -> onChange(h * 60 + m) },
                    start / 60,
                    start % 60,
                    false
                ).show()
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = minute?.let { minuteToText(it) } ?: "없음",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (allowClear && minute != null) {
            Spacer(Modifier.size(12.dp))
            TextButton(onClick = { onChange(null) }) { Text("지우기") }
        }
    }
}

data class RoutineDraft(
    val title: String = "",
    val days: List<Int> = listOf(1, 2, 3, 4, 5),
    val dueMinute: Int? = null,
    val remindBefore: Int = 60,
    val active: Boolean = true
)

@Composable
fun RoutineDialog(
    initial: RoutineDraft,
    isNew: Boolean,
    onSave: (RoutineDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "할 일 추가" else "할 일 수정") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text("무엇을 하나요?") },
                    placeholder = { Text("예: 수학 문제집 2장") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Text("하는 요일", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                DayPicker(draft.days) { draft = draft.copy(days = it) }
                Spacer(Modifier.height(8.dp))
                TimeField("마감 시각", draft.dueMinute) { draft = draft.copy(dueMinute = it) }
                if (draft.dueMinute != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "몇 분 전에 알릴까요",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        listOf(30, 60, 120).forEach { m ->
                            TextButton(onClick = { draft = draft.copy(remindBefore = m) }) {
                                Text(
                                    text = "${m}분",
                                    fontWeight = if (draft.remindBefore == m) FontWeight.Bold
                                    else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("사용 중", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = draft.active, onCheckedChange = { draft = draft.copy(active = it) })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (draft.title.isNotBlank()) onSave(draft) },
                enabled = draft.title.isNotBlank()
            ) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    )
}

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    placeholder: String = "",
    initial: String = "",
    confirmText: String = "저장",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank()
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
fun ReminderDialog(
    reminder: com.henny.checklist.data.Reminder,
    onSave: (com.henny.checklist.data.Reminder) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(reminder) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("점검 알림") },
        text = {
            Column {
                TimeField("알릴 시각", draft.minute, allowClear = false) {
                    draft = draft.copy(minute = it ?: draft.minute)
                }
                OutlinedTextField(
                    value = draft.text,
                    onValueChange = { draft = draft.copy(text = it) },
                    label = { Text("알림 문구") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Text("울리는 요일", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                DayPicker(draft.days) { draft = draft.copy(days = it) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "남은 할 일이 있을 때만",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = draft.onlyIfIncomplete,
                        onCheckedChange = { draft = draft.copy(onlyIfIncomplete = it) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = draft.text.isNotBlank()) {
                Text("저장")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
