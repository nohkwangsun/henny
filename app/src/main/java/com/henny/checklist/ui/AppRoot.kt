package com.henny.checklist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.henny.checklist.data.Repository
import com.henny.checklist.data.Role
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 알림 권한 요청은 Activity 가 쥐고 있으므로 화면 깊은 곳까지 이걸로 내려보낸다. */
val LocalNotificationRequester = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun AppRoot(repo: Repository) {
    val settings by repo.settings.collectAsStateWithLifecycle()
    val plan by repo.plan.collectAsStateWithLifecycle()
    val progress by repo.progress.collectAsStateWithLifecycle()
    val syncing by repo.syncing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    if (!settings.setupDone) {
        SetupScreen(repo = repo, plan = plan, settings = settings)
        return
    }

    when (settings.roleEnum) {
        Role.MANAGER -> ManagerScreen(
            repo = repo,
            plan = plan,
            settings = settings,
            syncing = syncing,
            onSync = { scope.launch { repo.sync() } }
        )

        else -> {
            var showSettings by remember { mutableStateOf(false) }
            BackHandler(enabled = showSettings) { showSettings = false }

            if (showSettings) {
                SettingsScreen(repo = repo, settings = settings, plan = plan, isManager = false)
            } else {
                val today = LocalDate.now()
                val workerId = settings.workerId
                val worker = plan.workers.firstOrNull { it.id == workerId }
                val index = plan.workers.indexOfFirst { it.id == workerId }.coerceAtLeast(0)

                // progress / plan 이 바뀔 때만 목록을 다시 만든다.
                val tasks = remember(progress, plan, workerId, today) {
                    repo.tasksFor(workerId, today)
                }
                val week = remember(progress, plan, workerId, today) {
                    repo.weekStat(workerId, today)
                }
                val streak = remember(progress, plan, workerId, today) {
                    repo.streak(workerId)
                }
                val lifetimePoints = remember(progress, workerId) {
                    repo.lifetimePoints(workerId)
                }

                WorkerScreen(
                    workerName = worker?.name ?: "나",
                    workerEmoji = worker?.emoji.orEmpty(),
                    accent = workerColor(index),
                    today = today,
                    tasks = tasks,
                    week = week,
                    streak = streak,
                    lifetimePoints = lifetimePoints,
                    syncing = syncing,
                    syncLabel = syncLabel(settings, today),
                    onSync = { scope.launch { repo.sync() } },
                    onToggle = { taskId -> repo.toggle(workerId, today, taskId) },
                    onOpenSettings = { showSettings = true }
                )
            }
        }
    }
}


/** 헤더에 보여줄 한 줄. 언제 맞췄는지 알면 지금 값이 최신인지 판단할 수 있다. */
private fun syncLabel(settings: com.henny.checklist.data.Settings, today: LocalDate): String {
    val names = listOf("월", "화", "수", "목", "금", "토", "일")
    val date = "${today.monthValue}월 ${today.dayOfMonth}일 ${names[today.dayOfWeek.value - 1]}요일"
    if (settings.backendEnum == com.henny.checklist.data.Backend.NONE) return date
    if (settings.lastSyncError.isNotBlank()) return "$date · 연결 안 됨"
    if (settings.lastSyncAt == 0L) return date
    val minutes = (System.currentTimeMillis() - settings.lastSyncAt) / 60000
    val when_ = when {
        minutes < 1 -> "방금 맞춤"
        minutes < 60 -> "${minutes}분 전 맞춤"
        else -> "${minutes / 60}시간 전 맞춤"
    }
    return "$date · $when_"
}
