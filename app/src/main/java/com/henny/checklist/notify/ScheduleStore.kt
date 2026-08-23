package com.henny.checklist.notify

import android.content.Context
import org.json.JSONArray

/**
 * 웹이 넘겨준 알림 일정을 그대로 보관한다.
 * 껍데기는 자료를 해석하지 않는다. "언제 무엇을 띄울지"만 알면 된다.
 *
 * ---------------------------------------------------------------------------
 * 이 클래스가 이 프로젝트 구조의 핵심 경계다
 *
 * 껍데기가 아는 것은 {시각, 제목, 내용, 꼬리표} 네 개뿐이다. 작업이 무엇인지,
 * 마일리지가 얼마인지, 요일 규칙이 어떻게 되는지는 전혀 모른다. 그 판단은 전부
 * 웹(web/core.js 의 computeSchedule)이 하고, 결과만 여기로 건너온다.
 *
 * 그래서 규칙을 아무리 바꿔도 이 파일은 그대로다 = APK 를 다시 만들 필요가 없다.
 * 반대로 이 네 개로 표현할 수 없는 알림(예: 버튼이 달린 알림, 소리를 다르게)을
 * 만들고 싶어지면 그때는 APK 를 다시 만들어야 한다.
 *
 * ---------------------------------------------------------------------------
 * SharedPreferences 는 그냥 파일이다
 *
 * 이름이 "설정"처럼 보이지만 실체는 앱 전용 디렉터리에 있는 XML 파일 하나이고,
 * 키-값을 넣고 빼는 용도다. DB 가 아니라 트랜잭션도 인덱스도 없다.
 * 몇 KB 수준의 작은 상태에만 쓴다. 여기서는 알림 일정 JSON 한 덩어리와
 * 마지막으로 띄운 시각 하나뿐이라 딱 맞는다.
 *
 * apply() 는 메모리에 먼저 반영하고 디스크 쓰기는 백그라운드로 넘긴다.
 * commit() 은 디스크까지 기다린다. 여기서는 값이 유실돼도 다음 실행 때 웹이
 * 다시 채워 주므로 빠른 쪽을 쓴다.
 */
object ScheduleStore {

    private const val PREFS = "henny_schedule"
    private const val KEY_JSON = "schedule"
    private const val KEY_FIRED = "lastFiredAt"

    /** at 은 epoch milliseconds. 웹의 Date.now() 와 같은 기준이다. */
    data class Entry(val at: Long, val title: String, val body: String, val tag: String)

    fun save(context: Context, json: String) {
        prefs(context).edit().putString(KEY_JSON, json).apply()
    }

    /**
     * 저장해 둔 JSON 을 Entry 목록으로 읽는다.
     *
     * 통째로 runCatching 으로 감싸고 실패하면 빈 목록을 준다. 여기서 예외가
     * 올라가면 알람 브로드캐스트 안에서 터져 앱이 죽는데, 사용자는 앱을 열지도
     * 않은 상태라 원인을 알 길이 없다. 알림이 안 오는 편이 낫다.
     * optLong/optString 도 같은 이유 — 필드가 빠져도 예외 대신 기본값을 준다.
     */
    fun load(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_JSON, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    at = o.optLong("at"),
                    title = o.optString("title"),
                    body = o.optString("body"),
                    tag = o.optString("tag")
                )
            }.filter { it.at > 0 }
        }.getOrDefault(emptyList())
    }

    /** 마지막으로 알림을 띄운 시각. 같은 알림을 두 번 띄우지 않기 위한 경계선. */
    fun lastFiredAt(context: Context): Long = prefs(context).getLong(KEY_FIRED, 0L)

    fun markFired(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_FIRED, at).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
