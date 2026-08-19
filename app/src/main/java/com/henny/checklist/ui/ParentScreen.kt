package com.henny.checklist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.henny.checklist.data.Child
import com.henny.checklist.data.Plan
import com.henny.checklist.data.Reminder
import com.henny.checklist.data.Repository
import com.henny.checklist.data.Routine
import com.henny.checklist.data.Settings
import com.henny.checklist.data.minuteToText
import java.time.LocalDate

enum class ParentTab { TODAY, STATS, MANAGE, SETTINGS }

@Composable
fun ParentScreen(
    repo: Repository,
    plan: Plan,
    settings: Settings,
    syncing: Boolean,
    onSync: () -> Unit
) {
    var tab by remember { mutableStateOf(ParentTab.TODAY) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == ParentTab.TODAY,
                    onClick = { tab = ParentTab.TODAY },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("오늘") }
                )
                NavigationBarItem(
                    selected = tab == ParentTab.STATS,
                    onClick = { tab = ParentTab.STATS },
                    icon = { Icon(Icons.Default.DateRange, null) },
                    label = { Text("통계") }
                )
                NavigationBarItem(
                    selected = tab == ParentTab.MANAGE,
                    onClick = { tab = ParentTab.MANAGE },
                    icon = { Icon(Icons.Default.Check, null) },
                    label = { Text("할 일") }
                )
                NavigationBarItem(
                    selected = tab == ParentTab.SETTINGS,
                    onClick = { tab = ParentTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("설정") }
                )
            }
        }
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            when (tab) {
                ParentTab.TODAY -> ParentToday(repo, plan, settings, syncing, onSync)
                ParentTab.STATS -> ParentStats(repo, plan)
                ParentTab.MANAGE -> ParentManage(repo, plan)
                ParentTab.SETTINGS -> SettingsScreen(repo, settings, plan, isParent = true)
            }
        }
    }
}

// ------------------------------------------------------------------- 오늘

@Composable
private fun ParentToday(
    repo: Repository,
    plan: Plan,
    settings: Settings,
    syncing: Boolean,
    onSync: () -> Unit
) {
    val today = LocalDate.now()
    var missionFor by remember { mutableStateOf<Child?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("오늘 현황", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = syncStatusText(settings, syncing),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (settings.lastSyncError.isBlank())
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = onSync, enabled = !syncing) {
                    Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                }
            }
        }

        if (plan.children.isEmpty()) {
            item {
                SectionCard {
                    Text("아직 등록된 아이가 없어요. 설정 탭에서 아이를 추가해 주세요.")
                }
            }
        }

        plan.children.forEachIndexed { index, child ->
            item(key = child.id) {
                val accent = childColor(index)
                val tasks = repo.tasksFor(child.id, today)
                val done = tasks.count { it.done }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${child.emoji} ${child.name}",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = when {
                                    tasks.isEmpty() -> "오늘은 할 일이 없어요"
                                    done == tasks.size -> "오늘 할 일 모두 완료 🎉"
                                    else -> "${tasks.size - done}개 남음"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ProgressRing(
                            done = done,
                            total = tasks.size,
                            color = accent,
                            diameter = 86.dp,
                            thickness = 10.dp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    tasks.forEach { task ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (task.done) accent
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = task.title + if (task.isMission) "  (미션)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (task.done) TextDecoration.LineThrough
                                else TextDecoration.None,
                                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            // 떨어져 있으면 "했는지"만큼 "언제 했는지"가 궁금하다.
                            val trailing = task.doneAt?.let { "${doneAtText(it)} 완료" }
                                ?: task.dueMinute?.let { "${minuteToText(it)}까지" }
                            if (trailing != null) {
                                Text(
                                    text = trailing,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { missionFor = child }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("오늘 미션 주기")
                    }
                }
            }
        }
    }

    missionFor?.let { child ->
        TextPromptDialog(
            title = "${child.name}에게 오늘 미션",
            label = "미션 내용",
            placeholder = "예: 가방 정리하기",
            confirmText = "보내기",
            onConfirm = {
                repo.addMission(child.id, it, today, null)
                missionFor = null
            },
            onDismiss = { missionFor = null }
        )
    }
}

private fun syncStatusText(settings: Settings, syncing: Boolean): String = when {
    syncing -> "동기화 중…"
    settings.lastSyncError.isNotBlank() -> settings.lastSyncError
    settings.lastSyncAt == 0L -> "아직 동기화한 적 없음"
    else -> {
        val minutes = (System.currentTimeMillis() - settings.lastSyncAt) / 60000
        when {
            minutes < 1 -> "방금 동기화됨"
            minutes < 60 -> "${minutes}분 전 동기화"
            else -> "${minutes / 60}시간 전 동기화"
        }
    }
}

// ------------------------------------------------------------------- 통계

@Composable
private fun ParentStats(repo: Repository, plan: Plan) {
    var selected by remember(plan.children.size) {
        mutableStateOf(plan.children.firstOrNull()?.id ?: "")
    }
    val index = plan.children.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val accent = childColor(index)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("통계", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        ChildChips(plan.children, selected) { selected = it }
        Spacer(Modifier.height(16.dp))

        if (selected.isBlank()) {
            SectionCard { Text("아이를 먼저 추가해 주세요.") }
            return@Column
        }

        val week = repo.weekStat(selected)
        val month = repo.monthStat(selected)

        SectionCard(title = "이번 주") {
            DayBars(perDay = week.perDay, color = accent)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatPill("달성률", "${week.rate}%", accent, Modifier.weight(1f))
                StatPill("해낸 일", "${week.done}개", accent, Modifier.weight(1f))
                StatPill("완벽한 날", "${week.perfectDays}일", accent, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))
        SectionCard(title = "${LocalDate.now().monthValue}월") {
            MonthDots(perDay = month.perDay, color = accent)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatPill("달성률", "${month.rate}%", accent, Modifier.weight(1f))
                StatPill("해낸 일", "${month.done}개", accent, Modifier.weight(1f))
                StatPill("완벽한 날", "${month.perfectDays}일", accent, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "전체 ${month.total}개 중 ${month.done}개 완료",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ChildChips(children: List<Child>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        children.forEachIndexed { index, child ->
            val on = child.id == selected
            val accent = childColor(index)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) accent else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(child.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "${child.emoji} ${child.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 할 일 관리

@Composable
private fun ParentManage(repo: Repository, plan: Plan) {
    var selected by remember(plan.children.size) {
        mutableStateOf(plan.children.firstOrNull()?.id ?: "")
    }
    var editing by remember { mutableStateOf<Routine?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<Reminder?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("할 일 관리", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        ChildChips(plan.children, selected) { selected = it }
        Spacer(Modifier.height(16.dp))

        if (selected.isBlank()) {
            SectionCard { Text("아이를 먼저 추가해 주세요.") }
            return@Column
        }

        SectionCard(title = "반복하는 할 일") {
            val routines = plan.routines.filter { it.childId == selected }.sortedBy { it.order }
            if (routines.isEmpty()) {
                Text(
                    "아직 없어요. 아래에서 추가해 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            routines.forEachIndexed { i, routine ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = routine }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = routine.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (routine.active) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = daysText(routine.days) +
                                (routine.dueMinute?.let { " · ${minuteToText(it)}까지" } ?: "") +
                                (if (routine.active) "" else " · 쉼"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "수정",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("할 일 추가")
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "점검 알림") {
            Text(
                "정해둔 시각에 아이 폰이 울려요.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            plan.reminders.filter { it.childId == selected }.sortedBy { it.minute }
                .forEach { reminder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { editingReminder = reminder }
                        ) {
                            Text(
                                text = minuteToText(reminder.minute),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = reminder.text +
                                    if (reminder.onlyIfIncomplete) " · 남았을 때만" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = reminder.enabled,
                            onCheckedChange = { repo.upsertReminder(reminder.copy(enabled = it)) }
                        )
                        TextButton(onClick = { repo.deleteReminder(reminder.id) }) {
                            Text("삭제", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            OutlinedButton(onClick = {
                editingReminder = Reminder(
                    id = Repository.newId("r"),
                    childId = selected,
                    minute = 17 * 60,
                    text = "할 일 확인하기"
                )
            }) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("알림 추가")
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (creating) {
        RoutineDialog(
            initial = RoutineDraft(),
            isNew = true,
            onSave = { draft ->
                repo.addRoutine(selected, draft.title, draft.days, draft.dueMinute)
                creating = false
            },
            onDelete = null,
            onDismiss = { creating = false }
        )
    }

    editing?.let { routine ->
        RoutineDialog(
            initial = RoutineDraft(
                title = routine.title,
                days = routine.days,
                dueMinute = routine.dueMinute,
                remindBefore = routine.remindBefore,
                active = routine.active
            ),
            isNew = false,
            onSave = { draft ->
                repo.updateRoutine(
                    routine.copy(
                        title = draft.title,
                        days = draft.days,
                        dueMinute = draft.dueMinute,
                        remindBefore = draft.remindBefore,
                        active = draft.active
                    )
                )
                editing = null
            },
            onDelete = {
                repo.deleteRoutine(routine.id)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    editingReminder?.let { reminder ->
        ReminderDialog(
            reminder = reminder,
            onSave = {
                repo.upsertReminder(it)
                editingReminder = null
            },
            onDismiss = { editingReminder = null }
        )
    }
}

/** 체크한 시각을 "오후 4:20" 처럼 보여준다. */
private fun doneAtText(epochMillis: Long): String {
    val time = java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
    return minuteToText(time.hour * 60 + time.minute)
}

private fun daysText(days: List<Int>): String {
    val names = listOf("월", "화", "수", "목", "금", "토", "일")
    return when {
        days.sorted() == listOf(1, 2, 3, 4, 5) -> "평일"
        days.sorted() == listOf(1, 2, 3, 4, 5, 6, 7) -> "매일"
        days.isEmpty() -> "없음"
        else -> days.sorted().joinToString(" ") { names[it - 1] }
    }
}
