package com.henny.checklist.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

fun LocalDate.key(): String = format(DATE_FMT)
fun LocalDate.monthKey(): String = format(MONTH_FMT)

/** 아이 한 명. */
@Serializable
data class Child(
    val id: String,
    val name: String,
    val emoji: String = "🦊",
    val colorSeed: Int = 0
)

/**
 * 반복 할 일. 한 번 정하면 지정한 요일마다 계속 나온다.
 * [days]는 ISO 요일(월=1 … 일=7).
 */
@Serializable
data class Routine(
    val id: String,
    val childId: String,
    val title: String,
    val days: List<Int> = listOf(1, 2, 3, 4, 5),
    val dueMinute: Int? = null,
    val remindBefore: Int = 60,
    val active: Boolean = true,
    val order: Int = 0
)

/** 부모가 그날 하루만 주는 미션. */
@Serializable
data class Mission(
    val id: String,
    val childId: String,
    val title: String,
    val date: String,
    val dueMinute: Int? = null,
    val remindBefore: Int = 60,
    val note: String = ""
)

/** 하루 중 정해진 시각에 울리는 점검 알림. */
@Serializable
data class Reminder(
    val id: String,
    val childId: String,
    val minute: Int,
    val text: String,
    val onlyIfIncomplete: Boolean = true,
    val days: List<Int> = listOf(1, 2, 3, 4, 5),
    val enabled: Boolean = true
)

/** 부모가 쓰고 아이들이 읽는 문서. */
@Serializable
data class Plan(
    val schema: Int = 1,
    val updatedAt: Long = 0L,
    val children: List<Child> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val missions: List<Mission> = emptyList(),
    val reminders: List<Reminder> = emptyList()
) {
    fun childrenOf(childId: String): Boolean = children.any { it.id == childId }
}

/** 하루치 스냅샷의 항목 하나. [doneAt]이 null이면 아직 안 함. */
@Serializable
data class LogItem(
    val taskId: String,
    val title: String,
    val doneAt: Long? = null
) {
    val done: Boolean get() = doneAt != null
}

@Serializable
data class DayLog(
    val date: String,
    val items: List<LogItem> = emptyList(),
    val updatedAt: Long = 0L
) {
    val doneCount: Int get() = items.count { it.done }
    val total: Int get() = items.size
}

/** 오래된 날짜를 접어둔 월별 합계. */
@Serializable
data class MonthRollup(val month: String, val done: Int, val total: Int)

/** 아이 한 명이 쓰고 부모가 읽는 문서. */
@Serializable
data class Progress(
    val schema: Int = 1,
    val childId: String = "",
    val updatedAt: Long = 0L,
    val days: Map<String, DayLog> = emptyMap(),
    val archive: List<MonthRollup> = emptyList()
)

enum class Role { NONE, KID, PARENT }

enum class Backend { NONE, JSONBIN, HTTP }

/** 기기마다 다른 설정. 절대 동기화하지 않는다. */
@Serializable
data class Settings(
    val role: String = Role.NONE.name,
    val childId: String = "",
    val backend: String = Backend.NONE.name,
    val apiKey: String = "",
    val planBin: String = "",
    /** childId -> bin id(JSONBin) 또는 전체 URL(HTTP) */
    val progressBins: Map<String, String> = emptyMap(),
    val setupDone: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastSyncError: String = "",
    /** 부모 기기에서 하루 한 번 받는 요약 알림 시각(자정부터 분). */
    val parentSummaryMinute: Int = 20 * 60 + 30,
    val parentSummaryOn: Boolean = true
) {
    val roleEnum: Role get() = runCatching { Role.valueOf(role) }.getOrDefault(Role.NONE)
    val backendEnum: Backend get() = runCatching { Backend.valueOf(backend) }.getOrDefault(Backend.NONE)
}

/** 화면에 그릴 오늘의 할 일 한 줄. */
data class TodayTask(
    val id: String,
    val title: String,
    val dueMinute: Int?,
    val remindBefore: Int,
    val isMission: Boolean,
    val doneAt: Long?
) {
    val done: Boolean get() = doneAt != null
}

fun minuteToText(minute: Int): String {
    val h = minute / 60
    val m = minute % 60
    val ampm = if (h < 12) "오전" else "오후"
    val h12 = when {
        h % 12 == 0 -> 12
        else -> h % 12
    }
    return if (m == 0) "$ampm ${h12}시" else "$ampm ${h12}시 ${m}분"
}
