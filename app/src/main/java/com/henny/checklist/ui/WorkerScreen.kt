package com.henny.checklist.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.henny.checklist.data.RangeStat
import com.henny.checklist.data.TodayTask
import com.henny.checklist.data.minuteToText
import java.time.LocalDate

/**
 * 작업자가 보는 화면은 이 하나뿐이다.
 * 켜면 바로 오늘 목록, 한 번 누르면 체크, 끝.
 */
@Composable
fun WorkerScreen(
    workerName: String,
    workerEmoji: String,
    accent: Color,
    today: LocalDate,
    tasks: List<TodayTask>,
    week: RangeStat,
    streak: Int,
    lifetimePoints: Int,
    syncing: Boolean,
    syncLabel: String,
    onSync: () -> Unit,
    onToggle: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val done = tasks.count { it.done }
    val total = tasks.size
    val allDone = total > 0 && done == total
    val earned = tasks.filter { it.done }.sumOf { it.points }
    val offered = tasks.sumOf { it.points }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (workerEmoji.isBlank()) workerName else "$workerEmoji $workerName",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (syncing) "맞추는 중…" else syncLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onSync, enabled = !syncing) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "지금 맞추기",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "설정",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    ProgressRing(done = done, total = total, color = accent)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        total == 0 -> "오늘 배정된 작업이 없습니다"
                        allDone -> "오늘 작업을 모두 마쳤습니다"
                        else -> "${total - done}개 남았습니다"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (allDone) accent else MaterialTheme.colorScheme.onBackground,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatPill("오늘 모은 마일리지", "${earned}P", accent, Modifier.weight(1f))
                    StatPill("오늘 걸린 마일리지", "${offered}P", accent, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }

            items(tasks, key = { it.id }) { task ->
                TaskRow(task = task, accent = accent, onToggle = { onToggle(task.id) })
            }

            item {
                Spacer(Modifier.height(10.dp))
                SectionCard(title = "이번 주") {
                    DayBars(perDay = week.perDay, color = accent)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatPill("달성률", "${week.rate}%", accent, Modifier.weight(1f))
                        StatPill("이번 주", "${week.points}P", accent, Modifier.weight(1f))
                        StatPill("연속", "${streak}일", accent, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "지금까지 모은 마일리지",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${lifetimePoints}P",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TodayTask, accent: Color, onToggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val bg = if (task.done) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
    val elevation by animateDpAsState(
        targetValue = if (task.done) 0.dp else 1.dp,
        animationSpec = tween(160),
        label = "elev"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            },
        color = bg,
        tonalElevation = elevation,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (task.done) accent
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(22.dp)
                        .alpha(if (task.done) 1f else 0f)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (task.isAssignment) {
                    Text(
                        text = "임시 작업",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (task.done) FontWeight.Normal else FontWeight.Medium,
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None
                )
                task.dueMinute?.let {
                    Text(
                        text = "${minuteToText(it)}까지",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${task.points}P",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (task.done) accent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
