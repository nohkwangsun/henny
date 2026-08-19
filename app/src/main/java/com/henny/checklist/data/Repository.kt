package com.henny.checklist.data

import android.content.Context
import android.util.Base64
import com.henny.checklist.notify.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

data class DayStat(val date: LocalDate, val done: Int, val total: Int)

data class RangeStat(val done: Int, val total: Int, val perDay: List<DayStat>) {
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
            addAll(_plan.value.children.map { it.id })
            _settings.value.childId.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        _progress.value = ids.associateWith { store.loadProgress(it) }
    }

    // ---------------------------------------------------------------- 설정

    fun updateSettings(block: (Settings) -> Settings) {
        val next = block(_settings.value)
        _settings.value = next
        store.saveSettings(next)
    }

    fun progressOf(childId: String): Progress =
        _progress.value[childId] ?: Progress(childId = childId)

    fun childName(childId: String): String =
        _plan.value.children.firstOrNull { it.id == childId }?.name ?: "우리 아이"

    fun child(childId: String): Child? = _plan.value.children.firstOrNull { it.id == childId }

    // ------------------------------------------------------------ 오늘 할 일

    fun tasksFor(childId: String, date: LocalDate): List<TodayTask> {
        val p = _plan.value
        val dow = date.dayOfWeek.value
        val dateKey = date.key()
        val doneMap = progressOf(childId).days[dateKey]
            ?.items?.associate { it.taskId to it.doneAt } ?: emptyMap()

        val missions = p.missions
            .filter { it.childId == childId && it.date == dateKey }
            .map { TodayTask(it.id, it.title, it.dueMinute, it.remindBefore, true, doneMap[it.id]) }

        val routines = p.routines
            .filter { it.childId == childId && it.active && dow in it.days }
            .sortedWith(compareBy({ it.order }, { it.title }))
            .map { TodayTask(it.id, it.title, it.dueMinute, it.remindBefore, false, doneMap[it.id]) }

        return missions + routines
    }

    fun expectedCount(childId: String, date: LocalDate): Int {
        val p = _plan.value
        val dow = date.dayOfWeek.value
        val dateKey = date.key()
        return p.routines.count { it.childId == childId && it.active && dow in it.days } +
            p.missions.count { it.childId == childId && it.date == dateKey }
    }

    fun remaining(childId: String, date: LocalDate): Int =
        tasksFor(childId, date).count { !it.done }

    /** 체크/체크해제. 그날의 목록 전체를 스냅샷으로 남겨서 나중에 할 일이 바뀌어도 통계가 흔들리지 않는다. */
    fun toggle(childId: String, date: LocalDate, taskId: String) {
        val now = System.currentTimeMillis()
        val items = tasksFor(childId, date).map { t ->
            val doneAt = when {
                t.id != taskId -> t.doneAt
                t.done -> null
                else -> now
            }
            LogItem(t.id, t.title, doneAt)
        }
        writeDay(childId, date, DayLog(date.key(), items, now))
    }

    private fun writeDay(childId: String, date: LocalDate, log: DayLog) {
        val current = progressOf(childId)
        val updated = current.copy(
            childId = childId,
            updatedAt = System.currentTimeMillis(),
            days = current.days + (date.key() to log)
        ).pruned()
        _progress.value = _progress.value + (childId to updated)
        store.saveProgress(updated)
        schedulePush()
    }

    /**
     * 아이는 보통 여러 개를 연달아 체크한다. 그때마다 네트워크를 두드리면
     * 무료 저장소 호출 한도만 잡아먹으므로 잠깐 모았다가 한 번에 올린다.
     */
    private fun schedulePush() {
        pendingPush?.cancel()
        pendingPush = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            runCatching { sync() }
        }
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
            val prev = merged[month] ?: MonthRollup(month, 0, 0)
            merged[month] = prev.copy(done = prev.done + log.doneCount, total = prev.total + log.total)
        }
        return copy(
            days = keep.associate { it.key to it.value },
            archive = merged.values.sortedBy { it.month }
        )
    }

    // ---------------------------------------------------------------- 통계

    fun statFor(childId: String, from: LocalDate, to: LocalDate): RangeStat {
        val today = LocalDate.now()
        val last = if (to.isAfter(today)) today else to
        val logs = progressOf(childId).days
        val perDay = mutableListOf<DayStat>()
        var d = from
        while (!d.isAfter(last)) {
            val log = logs[d.key()]
            perDay += if (log != null && log.total > 0) {
                DayStat(d, log.doneCount, log.total)
            } else {
                // 아이가 앱을 아예 안 연 날도 "해야 했던 만큼"을 총량에 넣는다.
                DayStat(d, 0, expectedCount(childId, d))
            }
            d = d.plusDays(1)
        }
        return RangeStat(perDay.sumOf { it.done }, perDay.sumOf { it.total }, perDay)
    }

    fun weekStat(childId: String, anchor: LocalDate = LocalDate.now()): RangeStat {
        val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        return statFor(childId, monday, monday.plusDays(6))
    }

    fun monthStat(childId: String, anchor: LocalDate = LocalDate.now()): RangeStat {
        val first = anchor.withDayOfMonth(1)
        return statFor(childId, first, first.plusMonths(1).minusDays(1))
    }

    /** 오늘까지 연속으로 "할 일을 다 한" 날 수. */
    fun streak(childId: String): Int {
        var count = 0
        var d = LocalDate.now()
        repeat(400) {
            val expected = progressOf(childId).days[d.key()]?.total ?: expectedCount(childId, d)
            if (expected == 0) {
                d = d.minusDays(1)
                return@repeat
            }
            val done = progressOf(childId).days[d.key()]?.doneCount ?: 0
            if (done >= expected) {
                count++
                d = d.minusDays(1)
            } else {
                return count
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

    fun addChild(name: String, emoji: String): Child {
        val child = Child(id = newId("c"), name = name.trim(), emoji = emoji)
        mutatePlan { p ->
            p.copy(
                children = p.children + child,
                reminders = p.reminders + defaultReminders(child.id)
            )
        }
        return child
    }

    private fun defaultReminders(childId: String) = listOf(
        Reminder(newId("r"), childId, 7 * 60 + 30, "오늘 할 일을 확인해요", onlyIfIncomplete = false),
        Reminder(newId("r"), childId, 16 * 60, "지금 하나만 시작해볼까?"),
        Reminder(newId("r"), childId, 20 * 60, "자기 전 마지막 점검!")
    )

    fun addRoutine(childId: String, title: String, days: List<Int>, dueMinute: Int?) {
        mutatePlan { p ->
            val order = (p.routines.filter { it.childId == childId }.maxOfOrNull { it.order } ?: 0) + 1
            p.copy(
                routines = p.routines + Routine(
                    id = newId("t"),
                    childId = childId,
                    title = title.trim(),
                    days = days,
                    dueMinute = dueMinute,
                    order = order
                )
            )
        }
    }

    fun updateRoutine(routine: Routine) =
        mutatePlan { p -> p.copy(routines = p.routines.map { if (it.id == routine.id) routine else it }) }

    fun deleteRoutine(id: String) =
        mutatePlan { p -> p.copy(routines = p.routines.filterNot { it.id == id }) }

    fun addMission(childId: String, title: String, date: LocalDate, dueMinute: Int?) {
        mutatePlan { p ->
            val fresh = p.missions.filterNot {
                runCatching { LocalDate.parse(it.date, DATE_FMT).isBefore(LocalDate.now().minusDays(45)) }
                    .getOrDefault(false)
            }
            p.copy(
                missions = fresh + Mission(
                    id = newId("m"),
                    childId = childId,
                    title = title.trim(),
                    date = date.key(),
                    dueMinute = dueMinute
                )
            )
        }
    }

    fun deleteMission(id: String) =
        mutatePlan { p -> p.copy(missions = p.missions.filterNot { it.id == id }) }

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
                    Role.PARENT -> syncAsParent(net, s)
                    Role.KID -> syncAsKid(net, s)
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
        // 계획은 부모가 주인. 다른 부모 기기가 더 최신이면 그쪽을 따른다.
        if (s.planBin.isNotBlank()) {
            val remotePlanText = net.get(s.planBin)
            val remotePlan = remotePlanText?.let { runCatching { store.decodePlan(it) }.getOrNull() }
            if (remotePlan != null && remotePlan.updatedAt > _plan.value.updatedAt) {
                _plan.value = remotePlan
                store.savePlan(remotePlan)
                reloadProgress()
            } else {
                net.put(s.planBin, store.encode(_plan.value))
            }
        }
        // 아이들의 기록은 읽기만 한다.
        _plan.value.children.forEach { child ->
            val handle = s.progressBins[child.id] ?: return@forEach
            val text = net.get(handle) ?: return@forEach
            val remoteProgress = runCatching { store.decodeProgress(text) }.getOrNull() ?: return@forEach
            val merged = mergeProgress(progressOf(child.id), remoteProgress.copy(childId = child.id))
            _progress.value = _progress.value + (child.id to merged)
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
        val handle = s.progressBins[s.childId]
        if (!handle.isNullOrBlank()) {
            val text = net.get(handle)
            val remoteProgress = text?.let { runCatching { store.decodeProgress(it) }.getOrNull() }
            val mine = progressOf(s.childId)
            val merged = if (remoteProgress == null) mine
            else mergeProgress(mine, remoteProgress.copy(childId = s.childId))
            if (merged != mine) {
                _progress.value = _progress.value + (s.childId to merged)
                store.saveProgress(merged)
            }
            net.put(handle, store.encode(merged))
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
                MonthRollup(month, list.maxOf { it.done }, list.maxOf { it.total })
            }
            .sortedBy { it.month }
        return a.copy(
            childId = a.childId.ifBlank { b.childId },
            updatedAt = maxOf(a.updatedAt, b.updatedAt),
            days = days,
            archive = archive
        )
    }

    // ------------------------------------------------------------ 연결 코드

    /** 아이 기기에 붙여넣을 코드. 백엔드 접속 정보 + 그 아이의 문서 손잡이. */
    fun pairingCode(childId: String): String {
        val s = _settings.value
        val payload = buildString {
            append("{")
            append("\"v\":1,")
            append("\"b\":\"${s.backend}\",")
            append("\"k\":\"${s.apiKey.escape()}\",")
            append("\"p\":\"${s.planBin.escape()}\",")
            append("\"c\":\"${childId.escape()}\",")
            append("\"g\":\"${(s.progressBins[childId] ?: "").escape()}\",")
            append("\"n\":\"${childName(childId).escape()}\"")
            append("}")
        }
        val b64 = Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
        return "HENNY1:$b64"
    }

    /** 아이 기기에서 코드를 받아 설정을 채운다. */
    fun applyPairingCode(raw: String): Result<String> = runCatching {
        val body = raw.trim().removePrefix("HENNY1:").trim()
        val text = String(Base64.decode(body, Base64.URL_SAFE), Charsets.UTF_8)
        fun field(name: String): String {
            val m = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)
                ?: return ""
            return m.groupValues[1].unescape()
        }
        val childId = field("c")
        require(childId.isNotBlank()) { "코드에 아이 정보가 없습니다." }
        updateSettings {
            it.copy(
                role = Role.KID.name,
                childId = childId,
                backend = field("b").ifBlank { Backend.NONE.name },
                apiKey = field("k"),
                planBin = field("p"),
                progressBins = mapOf(childId to field("g")),
                setupDone = true
            )
        }
        reloadProgress()
        field("n").ifBlank { "우리 아이" }
    }

    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    private fun String.unescape() = replace("\\\"", "\"").replace("\\\\", "\\")

    /** 부모 기기: 계획 + 아이별 기록용 저장 공간을 한 번에 만든다. */
    suspend fun provisionBins(): Result<Unit> = runCatching {
        val s = _settings.value
        val net = remote()
        require(net.configured) { "먼저 저장소를 고르고 열쇠를 넣어주세요." }
        var planBin = s.planBin
        if (planBin.isBlank()) {
            planBin = net.create("henny-plan", "{\"schema\":1,\"updatedAt\":0}")
        }
        val bins = s.progressBins.toMutableMap()
        _plan.value.children.forEach { child ->
            if (bins[child.id].isNullOrBlank()) {
                bins[child.id] = net.create("henny-progress-${child.name}", "{\"schema\":1,\"updatedAt\":0}")
            }
        }
        updateSettings { it.copy(planBin = planBin, progressBins = bins) }
        withContext(Dispatchers.IO) { net.put(planBin, store.encode(_plan.value)) }
    }

    companion object {
        private const val PUSH_DEBOUNCE_MS = 12_000L

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
