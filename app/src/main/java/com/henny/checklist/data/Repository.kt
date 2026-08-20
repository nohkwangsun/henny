package com.henny.checklist.data

import android.content.Context
import android.util.Base64
import com.henny.checklist.notify.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

data class DayStat(
    val date: LocalDate,
    val done: Int,
    val total: Int,
    val points: Int = 0
)

data class RangeStat(
    val done: Int,
    val total: Int,
    val perDay: List<DayStat>,
    /** 이 기간에 실제로 획득한 마일리지 합계. */
    val points: Int = 0
) {
    val rate: Int get() = if (total == 0) 0 else (done * 100) / total
    /** 할 일이 하나라도 있었고 전부 끝낸 날. */
    val perfectDays: Int get() = perDay.count { it.total > 0 && it.done == it.total }
}

/**
 * 앱 전체의 단일 상태 보관소.
 * 로컬 파일이 언제나 원본이고, 원격 저장소는 "가족끼리 주고받는 우체통"으로만 쓴다.
 */
class Repository private constructor(private val app: Context) {

    private val store = LocalStore(app)
    private val syncLock = Mutex()
    private var pendingPush: Job? = null
    private var liveJob: Job? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _settings = MutableStateFlow(store.loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _plan = MutableStateFlow(store.loadPlan())
    val plan: StateFlow<Plan> = _plan.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        reloadProgress()
    }

    private fun reloadProgress() {
        val ids = buildSet {
            addAll(_plan.value.workers.map { it.id })
            _settings.value.workerId.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        _progress.value = ids.associateWith { store.loadProgress(it) }
    }

    // ---------------------------------------------------------------- 설정

    fun updateSettings(block: (Settings) -> Settings) {
        val next = block(_settings.value)
        _settings.value = next
        store.saveSettings(next)
    }

    fun progressOf(workerId: String): Progress =
        _progress.value[workerId] ?: Progress(workerId = workerId)

    /** 형식이 안 맞아 밀려난 파일 목록. 설정 화면에서 알려 주려고 쓴다. */
    fun brokenFiles(): List<String> = store.brokenFiles()

    fun clearBrokenFiles() = store.clearBrokenFiles()

    fun workerName(workerId: String): String =
        _plan.value.workers.firstOrNull { it.id == workerId }?.name ?: "이름 없음"

    fun worker(workerId: String): Worker? = _plan.value.workers.firstOrNull { it.id == workerId }

    // ------------------------------------------------------------ 오늘 할 일

    fun tasksFor(workerId: String, date: LocalDate): List<TodayTask> {
        val p = _plan.value
        val dow = date.dayOfWeek.value
        val dateKey = date.key()
        val doneMap = progressOf(workerId).days[dateKey]
            ?.items?.associate { it.taskId to it.doneAt } ?: emptyMap()

        val assignments = p.assignments
            .filter { it.workerId == workerId && it.date == dateKey }
            .map {
                TodayTask(it.id, it.title, it.dueMinute, it.remindBefore, true, doneMap[it.id], it.points)
            }

        val routines = p.routines
            .filter { it.workerId == workerId && it.active && dow in it.days }
            .sortedWith(compareBy({ it.order }, { it.title }))
            .map {
                TodayTask(it.id, it.title, it.dueMinute, it.remindBefore, false, doneMap[it.id], it.points)
            }

        return assignments + routines
    }

    fun expectedCount(workerId: String, date: LocalDate): Int {
        val p = _plan.value
        val dow = date.dayOfWeek.value
        val dateKey = date.key()
        return p.routines.count { it.workerId == workerId && it.active && dow in it.days } +
            p.assignments.count { it.workerId == workerId && it.date == dateKey }
    }

    fun remaining(workerId: String, date: LocalDate): Int =
        tasksFor(workerId, date).count { !it.done }

    /** 체크/체크해제. 그날의 목록 전체를 스냅샷으로 남겨서 나중에 할 일이 바뀌어도 통계가 흔들리지 않는다. */
    fun toggle(workerId: String, date: LocalDate, taskId: String) {
        val now = System.currentTimeMillis()
        val items = tasksFor(workerId, date).map { t ->
            val doneAt = when {
                t.id != taskId -> t.doneAt
                t.done -> null
                else -> now
            }
            LogItem(t.id, t.title, doneAt, t.points)
        }
        writeDay(workerId, date, DayLog(date.key(), items, now))
    }

    private fun writeDay(workerId: String, date: LocalDate, log: DayLog) {
        val current = progressOf(workerId)
        val updated = current.copy(
            workerId = workerId,
            updatedAt = System.currentTimeMillis(),
            days = current.days + (date.key() to log)
        ).pruned()
        _progress.value = _progress.value + (workerId to updated)
        store.saveProgress(updated)
        schedulePush()
    }

    /**
     * 작업자는 보통 여러 개를 연달아 체크한다. 그때마다 네트워크를 두드리면
     * 무료 저장소 호출 한도만 잡아먹으므로 잠깐 모았다가 한 번에 올린다.
     */
    private fun schedulePush() {
        pendingPush?.cancel()
        pendingPush = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            runCatching { sync() }
        }
    }

    // ------------------------------------------------------ 화면이 켜져 있는 동안

    /**
     * 앱이 화면에 보이는 동안만 도는 동기화 루프.
     *
     * 매번 문서를 통째로 받으면 비싸므로, 가능한 백엔드에서는 `updatedAt` 한 값만
     * 읽어 보고 달라졌을 때만 전체를 받는다. 확인 한 번이 수십 바이트라
     * 짧은 주기로 돌려도 부담이 없다.
     */
    fun startLiveSync() {
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            runCatching { sync() }
            while (isActive) {
                delay(if (remote().supportsFieldFetch) LIVE_TICK_CHEAP else LIVE_TICK_FULL)
                runCatching { pollForChanges() }
            }
        }
    }

    /** 화면에서 벗어날 때. 미뤄둔 업로드는 마저 끝낸다. */
    fun stopLiveSync() {
        liveJob?.cancel()
        liveJob = null
        flushPush()
    }

    private suspend fun pollForChanges() {
        val s = _settings.value
        val net = remote()
        if (!net.configured) return
        if (!net.supportsFieldFetch) {
            // 값싼 확인이 안 되는 백엔드는 그냥 전체를 받는다. 대신 주기가 길다.
            sync()
            return
        }

        var changed = false
        if (s.planBin.isNotBlank()) {
            val at = net.getUpdatedAt(s.planBin)
            if (at != null && at > _plan.value.updatedAt) changed = true
        }
        // 내 기록은 내가 주인이라 확인할 필요가 없다. 관리자만 남의 기록을 살핀다.
        if (!changed && s.roleEnum == Role.MANAGER) {
            for (worker in _plan.value.workers) {
                val handle = s.progressBins[worker.id] ?: continue
                val at = net.getUpdatedAt(handle) ?: continue
                if (at > progressOf(worker.id).updatedAt) {
                    changed = true
                    break
                }
            }
        }
        if (changed) sync()
    }

    /** 앱이 화면에서 내려갈 때처럼, 미뤄둔 업로드를 지금 끝내야 할 때. */
    fun flushPush() {
        if (pendingPush?.isActive == true) {
            pendingPush?.cancel()
            pendingPush = null
            scope.launch { runCatching { sync() } }
        }
    }

    /** 오래된 날짜는 월별 합계로 접어서 문서 크기를 작게 유지한다. */
    private fun Progress.pruned(keepDays: Long = 150): Progress {
        val cutoff = LocalDate.now().minusDays(keepDays)
        val (old, keep) = days.entries.partition {
            runCatching { LocalDate.parse(it.key, DATE_FMT).isBefore(cutoff) }.getOrDefault(false)
        }
        if (old.isEmpty()) return this
        val merged = archive.associate { it.month to it }.toMutableMap()
        old.forEach { (dateKey, log) ->
            val month = dateKey.substring(0, 7)
            val prev = merged[month] ?: MonthRollup(month, 0, 0, 0)
            merged[month] = prev.copy(
                done = prev.done + log.doneCount,
                total = prev.total + log.total,
                points = prev.points + log.earnedPoints
            )
        }
        return copy(
            days = keep.associate { it.key to it.value },
            archive = merged.values.sortedBy { it.month }
        )
    }

    // ---------------------------------------------------------------- 통계

    fun statFor(workerId: String, from: LocalDate, to: LocalDate): RangeStat {
        val today = LocalDate.now()
        val last = if (to.isAfter(today)) today else to
        val logs = progressOf(workerId).days
        val perDay = mutableListOf<DayStat>()
        var d = from
        while (!d.isAfter(last)) {
            val log = logs[d.key()]
            perDay += if (log != null && log.total > 0) {
                DayStat(d, log.doneCount, log.total, log.earnedPoints)
            } else {
                // 작업자가 앱을 아예 안 연 날도 "해야 했던 만큼"을 총량에 넣는다.
                DayStat(d, 0, expectedCount(workerId, d), 0)
            }
            d = d.plusDays(1)
        }
        return RangeStat(
            done = perDay.sumOf { it.done },
            total = perDay.sumOf { it.total },
            perDay = perDay,
            points = perDay.sumOf { it.points }
        )
    }

    /** 지금까지 이 작업자가 모은 마일리지 전부. 접어둔 월별 합계까지 더한다. */
    fun lifetimePoints(workerId: String): Int {
        val p = progressOf(workerId)
        return p.days.values.sumOf { it.earnedPoints } + p.archive.sumOf { it.points }
    }

    /** 오늘 걸려 있는 배점 중 지금까지 챙긴 몫. */
    fun pointsToday(workerId: String, date: LocalDate = LocalDate.now()): Pair<Int, Int> {
        val tasks = tasksFor(workerId, date)
        return tasks.filter { it.done }.sumOf { it.points } to tasks.sumOf { it.points }
    }

    fun weekStat(workerId: String, anchor: LocalDate = LocalDate.now()): RangeStat {
        val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        return statFor(workerId, monday, monday.plusDays(6))
    }

    fun monthStat(workerId: String, anchor: LocalDate = LocalDate.now()): RangeStat {
        val first = anchor.withDayOfMonth(1)
        return statFor(workerId, first, first.plusMonths(1).minusDays(1))
    }

    /**
     * 연속으로 "할 일을 다 한" 날 수.
     * 할 일이 없던 날(주말 등)은 건너뛰고, 아직 진행 중인 오늘은 연속을 끊지 않는다.
     */
    fun streak(workerId: String): Int {
        val today = LocalDate.now()
        val logs = progressOf(workerId).days
        var count = 0
        var d = today
        repeat(400) {
            val expected = logs[d.key()]?.total ?: expectedCount(workerId, d)
            if (expected == 0) {
                d = d.minusDays(1)
                return@repeat
            }
            val done = logs[d.key()]?.doneCount ?: 0
            when {
                done >= expected -> {
                    count++
                    d = d.minusDays(1)
                }
                // 오늘은 아직 하루가 안 끝났으니 실패로 세지 않는다.
                d == today -> d = d.minusDays(1)
                else -> return count
            }
        }
        return count
    }

    // ------------------------------------------------------------ 계획 수정

    fun mutatePlan(block: (Plan) -> Plan) {
        val next = block(_plan.value).copy(updatedAt = System.currentTimeMillis())
        _plan.value = next
        store.savePlan(next)
        reloadProgress()
        AlarmScheduler.reschedule(app)
        scope.launch { runCatching { sync() } }
    }

    fun addWorker(name: String, emoji: String): Worker {
        val worker = Worker(id = newId("c"), name = name.trim(), emoji = emoji)
        mutatePlan { p ->
            p.copy(
                workers = p.workers + worker,
                reminders = p.reminders + defaultReminders(worker.id)
            )
        }
        return worker
    }

    private fun defaultReminders(workerId: String) = listOf(
        Reminder(newId("r"), workerId, 7 * 60 + 30, "오늘 작업을 확인하세요", onlyIfIncomplete = false),
        Reminder(newId("r"), workerId, 16 * 60, "아직 시작하지 않은 작업이 있습니다"),
        Reminder(newId("r"), workerId, 20 * 60, "마감 전 최종 점검")
    )

    fun renameWorker(workerId: String, name: String) = mutatePlan { p ->
        p.copy(
            workers = p.workers.map {
                if (it.id == workerId) it.copy(name = name.trim()) else it
            }
        )
    }

    /** 작업자를 지우면 그 작업자에게 달린 정기 작업·임시 작업·알림도 같이 사라진다. */
    fun deleteWorker(workerId: String) {
        mutatePlan { p ->
            p.copy(
                workers = p.workers.filterNot { it.id == workerId },
                routines = p.routines.filterNot { it.workerId == workerId },
                assignments = p.assignments.filterNot { it.workerId == workerId },
                reminders = p.reminders.filterNot { it.workerId == workerId }
            )
        }
        updateSettings { it.copy(progressBins = it.progressBins - workerId) }
    }

    fun addRoutine(
        workerId: String,
        title: String,
        days: List<Int>,
        dueMinute: Int?,
        points: Int = DEFAULT_POINTS
    ) {
        mutatePlan { p ->
            val order = (p.routines.filter { it.workerId == workerId }.maxOfOrNull { it.order } ?: 0) + 1
            p.copy(
                routines = p.routines + Routine(
                    id = newId("t"),
                    workerId = workerId,
                    title = title.trim(),
                    days = days,
                    dueMinute = dueMinute,
                    order = order,
                    points = points
                )
            )
        }
    }

    fun updateRoutine(routine: Routine) =
        mutatePlan { p -> p.copy(routines = p.routines.map { if (it.id == routine.id) routine else it }) }

    fun deleteRoutine(id: String) =
        mutatePlan { p -> p.copy(routines = p.routines.filterNot { it.id == id }) }

    fun addAssignment(
        workerId: String,
        title: String,
        date: LocalDate,
        dueMinute: Int?,
        points: Int = DEFAULT_POINTS
    ) {
        mutatePlan { p ->
            val fresh = p.assignments.filterNot {
                runCatching { LocalDate.parse(it.date, DATE_FMT).isBefore(LocalDate.now().minusDays(45)) }
                    .getOrDefault(false)
            }
            p.copy(
                assignments = fresh + Assignment(
                    id = newId("m"),
                    workerId = workerId,
                    title = title.trim(),
                    date = date.key(),
                    dueMinute = dueMinute,
                    points = points
                )
            )
        }
    }

    fun deleteAssignment(id: String) =
        mutatePlan { p -> p.copy(assignments = p.assignments.filterNot { it.id == id }) }

    fun upsertReminder(reminder: Reminder) = mutatePlan { p ->
        val exists = p.reminders.any { it.id == reminder.id }
        p.copy(
            reminders = if (exists) p.reminders.map { if (it.id == reminder.id) reminder else it }
            else p.reminders + reminder
        )
    }

    fun deleteReminder(id: String) =
        mutatePlan { p -> p.copy(reminders = p.reminders.filterNot { it.id == id }) }

    // ---------------------------------------------------------------- 동기화

    private fun remote(): RemoteStore {
        val s = _settings.value
        return RemoteStore(s.backendEnum, s.apiKey)
    }

    suspend fun sync(): Result<Unit> = syncLock.withLock {
        val s = _settings.value
        val net = remote()
        if (!net.configured) return@withLock Result.success(Unit)
        _syncing.value = true
        try {
            // 파일 쓰기와 네트워크가 섞여 있으므로 통째로 IO 스레드에서 돈다.
            withContext(Dispatchers.IO) {
                when (s.roleEnum) {
                    Role.MANAGER -> syncAsParent(net, s)
                    Role.WORKER -> syncAsKid(net, s)
                    Role.NONE -> Unit
                }
            }
            updateSettings { it.copy(lastSyncAt = System.currentTimeMillis(), lastSyncError = "") }
            Result.success(Unit)
        } catch (e: Throwable) {
            updateSettings { it.copy(lastSyncError = e.message ?: "동기화 실패") }
            Result.failure(e)
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun syncAsParent(net: RemoteStore, s: Settings) {
        // 계획은 관리자가 주인. 다른 관리자 기기가 더 최신이면 그쪽을 따른다.
        if (s.planBin.isNotBlank()) {
            val remotePlanText = net.get(s.planBin)
            val remotePlan = remotePlanText?.let { runCatching { store.decodePlan(it) }.getOrNull() }
            when {
                remotePlan != null && remotePlan.updatedAt > _plan.value.updatedAt -> {
                    _plan.value = remotePlan
                    store.savePlan(remotePlan)
                    reloadProgress()
                }
                // 양쪽이 같으면 올릴 것이 없다. 매번 PUT 하면 호출만 낭비된다.
                remotePlan != null && remotePlan.updatedAt == _plan.value.updatedAt -> Unit
                else -> net.put(s.planBin, store.encode(_plan.value))
            }
        }
        // 작업자들의 기록은 읽기만 한다.
        _plan.value.workers.forEach { worker ->
            val handle = s.progressBins[worker.id] ?: return@forEach
            val text = net.get(handle) ?: return@forEach
            val remoteProgress = runCatching { store.decodeProgress(text) }.getOrNull() ?: return@forEach
            val merged = mergeProgress(progressOf(worker.id), remoteProgress.copy(workerId = worker.id))
            _progress.value = _progress.value + (worker.id to merged)
            store.saveProgress(merged)
        }
    }

    private suspend fun syncAsKid(net: RemoteStore, s: Settings) {
        // 계획은 받아만 온다.
        if (s.planBin.isNotBlank()) {
            val text = net.get(s.planBin)
            val remotePlan = text?.let { runCatching { store.decodePlan(it) }.getOrNull() }
            if (remotePlan != null && remotePlan.updatedAt >= _plan.value.updatedAt) {
                _plan.value = remotePlan
                store.savePlan(remotePlan)
            }
        }
        // 내 기록은 내가 주인. 기기를 바꿨을 수도 있으니 합친 뒤 올린다.
        val handle = s.progressBins[s.workerId]
        if (!handle.isNullOrBlank()) {
            val text = net.get(handle)
            val remoteProgress = text?.let { runCatching { store.decodeProgress(it) }.getOrNull() }
            val mine = progressOf(s.workerId)
            val merged = if (remoteProgress == null) mine
            else mergeProgress(mine, remoteProgress.copy(workerId = s.workerId))
            if (merged != mine) {
                _progress.value = _progress.value + (s.workerId to merged)
                store.saveProgress(merged)
            }
            // 내 기록의 주인은 나다. 원격이 이미 같은 시점이면 올릴 필요가 없다.
            if (remoteProgress == null || merged.updatedAt > remoteProgress.updatedAt) {
                net.put(handle, store.encode(merged))
            }
        }
    }

    /** 날짜별로 더 최근에 손댄 쪽을 채택한다. */
    private fun mergeProgress(a: Progress, b: Progress): Progress {
        val days = HashMap(a.days)
        b.days.forEach { (k, v) ->
            val cur = days[k]
            if (cur == null || v.updatedAt > cur.updatedAt) days[k] = v
        }
        val archive = (a.archive + b.archive)
            .groupBy { it.month }
            .map { (month, list) ->
                MonthRollup(
                    month,
                    list.maxOf { it.done },
                    list.maxOf { it.total },
                    list.maxOf { it.points }
                )
            }
            .sortedBy { it.month }
        return a.copy(
            workerId = a.workerId.ifBlank { b.workerId },
            updatedAt = maxOf(a.updatedAt, b.updatedAt),
            days = days,
            archive = archive
        )
    }

    /**
     * 이 가족이 남긴 자료를 원격과 이 폰 양쪽에서 지운다.
     * Play 의 "사용자가 데이터 삭제를 요청할 수 있는가"에 대한 답이기도 하다.
     * 저장소 접속 설정은 남겨 두므로 지운 뒤 바로 다시 쓸 수 있다.
     */
    suspend fun wipeAllData(): Result<Unit> = runCatching {
        val s = _settings.value
        val net = remote()
        if (net.configured) {
            withContext(Dispatchers.IO) {
                s.progressBins.values.filter { it.isNotBlank() }.forEach { handle ->
                    net.put(handle, EMPTY_DOC)
                }
                if (s.roleEnum == Role.MANAGER && s.planBin.isNotBlank()) {
                    net.put(s.planBin, EMPTY_DOC)
                }
            }
        }
        val cleared = _progress.value.keys.associateWith { Progress(workerId = it) }
        cleared.values.forEach { store.saveProgress(it) }
        _progress.value = cleared

        val emptyPlan = Plan(updatedAt = System.currentTimeMillis())
        _plan.value = emptyPlan
        store.savePlan(emptyPlan)
        AlarmScheduler.reschedule(app)
    }

    // ------------------------------------------------------------ 연결 코드

    /**
     * 기기끼리 주고받는 연결 코드의 알맹이.
     *
     * 작업자용은 그 작업자의 문서 하나만 담고, 관리자용은 모든 문서를 담는다.
     * 관리자용은 곧 **복구 코드**이기도 하다. 폰을 갈아엎거나 앱을 지웠다 깔아도
     * 이 코드만 있으면 원래 쓰던 저장 경로로 그대로 돌아온다.
     */
    @Serializable
    private data class PairPayload(
        val v: Int = 2,
        val role: String = Role.WORKER.name,
        val backend: String = Backend.NONE.name,
        val apiKey: String = "",
        val firebaseDb: String = "",
        val planBin: String = "",
        val bins: Map<String, String> = emptyMap(),
        val name: String = ""
    )

    private fun encodeCode(payload: PairPayload): String {
        val body = Base64.encodeToString(
            store.encodeAny(payload).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
        return "HENNY2:$body"
    }

    /** 작업자 기기에 붙여넣을 코드. 그 작업자의 문서 하나만 담는다. */
    fun pairingCode(workerId: String): String {
        val s = _settings.value
        return encodeCode(
            PairPayload(
                role = Role.WORKER.name,
                backend = s.backend,
                apiKey = s.apiKey,
                firebaseDb = s.firebaseDb,
                planBin = s.planBin,
                bins = mapOf(workerId to (s.progressBins[workerId] ?: "")),
                name = workerName(workerId)
            )
        )
    }

    /**
     * 관리자 기기 복구 코드. 저장 경로 전부를 담는다.
     * 앱을 지웠다 깔아도 이 코드를 넣으면 기존 자료로 되돌아온다.
     */
    fun managerBackupCode(): String {
        val s = _settings.value
        return encodeCode(
            PairPayload(
                role = Role.MANAGER.name,
                backend = s.backend,
                apiKey = s.apiKey,
                firebaseDb = s.firebaseDb,
                planBin = s.planBin,
                bins = s.progressBins,
                name = "관리자"
            )
        )
    }

    /** 연결 코드나 복구 코드를 받아 이 기기 설정을 채운다. */
    fun applyPairingCode(raw: String): Result<String> = runCatching {
        val body = raw.trim().removePrefix("HENNY2:").removePrefix("HENNY1:").trim()
        val text = String(Base64.decode(body, Base64.URL_SAFE), Charsets.UTF_8)
        val payload = store.decodeAny<PairPayload>(text)

        val isManager = payload.role == Role.MANAGER.name
        val workerId = payload.bins.keys.firstOrNull().orEmpty()
        require(isManager || workerId.isNotBlank()) { "코드에 작업자 정보가 없습니다." }

        updateSettings {
            it.copy(
                role = payload.role,
                workerId = if (isManager) "" else workerId,
                backend = payload.backend.ifBlank { Backend.NONE.name },
                apiKey = payload.apiKey,
                firebaseDb = payload.firebaseDb,
                planBin = payload.planBin,
                progressBins = payload.bins,
                setupDone = true
            )
        }
        reloadProgress()
        payload.name.ifBlank { if (isManager) "관리자" else "작업자" }
    }

    /** 관리자 기기: 계획 + 작업자별 기록용 저장 공간을 준비한다. */
    suspend fun provisionBins(): Result<Unit> = runCatching {
        val s = _settings.value
        val net = remote()
        require(net.configured) { "먼저 저장소를 고르고 열쇠를 넣어주세요." }

        val planBin: String
        val bins: MutableMap<String, String>

        if (s.backendEnum == Backend.FIREBASE) {
            // Realtime Database 는 쓰는 순간 경로가 생기므로 미리 만들 것이 없다.
            val base = s.firebaseDb.trim().trimEnd('/')
            require(base.startsWith("https://")) { "Firebase 데이터베이스 주소를 확인해 주세요." }
            // 경로 한 칸을 추측 불가능한 값으로 두면, 규칙만으로도 남이 우리 자료를
            // 찾아낼 수 없다. 이미 만들어 둔 값이 있으면 그대로 다시 쓴다.
            // 이미 쓰던 경로가 있으면 반드시 그대로 다시 쓴다. 새로 만들면 그동안 쌓인
            // 자료가 통째로 고아가 된다. 기기를 갈아엎었다면 복구 코드로 planBin 을
            // 먼저 되돌려 놓고 이 함수를 부르는 것이 정상 경로다.
            val family = FAMILY_PATH.find(s.planBin)?.groupValues?.get(1)
                ?: UUID.randomUUID().toString().replace("-", "")
            planBin = "$base/henny/$family/plan.json"
            bins = s.progressBins.toMutableMap()
            _plan.value.workers.forEach { worker ->
                bins[worker.id] = "$base/henny/$family/progress/${worker.id}.json"
            }
        } else {
            var created = s.planBin
            if (created.isBlank()) {
                created = net.create("henny-plan", EMPTY_DOC)
            }
            planBin = created
            bins = s.progressBins.toMutableMap()
            _plan.value.workers.forEach { worker ->
                if (bins[worker.id].isNullOrBlank()) {
                    // 이름은 한글일 수 있는데 HTTP 헤더에는 ASCII 만 넣을 수 있으므로 id 를 쓴다.
                    bins[worker.id] = net.create("henny-progress-${worker.id}", EMPTY_DOC)
                }
            }
        }

        updateSettings { it.copy(planBin = planBin, progressBins = bins) }
        withContext(Dispatchers.IO) { net.put(planBin, store.encode(_plan.value)) }
    }

    companion object {
        /** 체크를 연달아 누를 때만 묶어 주면 되므로 짧게 잡는다. */
        private const val PUSH_DEBOUNCE_MS = 2_000L

        /** updatedAt 한 값만 읽는 백엔드(Firebase)에서 쓰는 확인 주기. */
        private const val LIVE_TICK_CHEAP = 15_000L

        /** 문서를 통째로 받아야 하는 백엔드에서 쓰는 확인 주기. */
        private const val LIVE_TICK_FULL = 60_000L
        private const val EMPTY_DOC = "{\"schema\":1,\"updatedAt\":0}"
        private val FAMILY_PATH = Regex("/henny/([^/]+)/plan\\.json$")

        @Volatile
        private var instance: Repository? = null

        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context.applicationContext).also { instance = it }
            }

        fun newId(prefix: String): String =
            prefix + "_" + UUID.randomUUID().toString().substring(0, 8)
    }
}
