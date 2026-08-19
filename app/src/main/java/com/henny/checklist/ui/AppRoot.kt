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

                WorkerScreen(
                    workerName = worker?.name ?: "나",
                    workerEmoji = worker?.emoji.orEmpty(),
                    accent = workerColor(index),
                    today = today,
                    tasks = tasks,
                    week = week,
                    streak = streak,
                    onToggle = { taskId -> repo.toggle(workerId, today, taskId) },
                    onOpenSettings = { showSettings = true }
                )
            }
        }
    }
}
