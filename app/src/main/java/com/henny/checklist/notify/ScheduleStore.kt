package com.henny.checklist.notify

import android.content.Context
import org.json.JSONArray

/**
 * 웹이 넘겨준 알림 일정을 그대로 보관한다.
 * 껍데기는 자료를 해석하지 않는다. "언제 무엇을 띄울지"만 알면 된다.
 */
object ScheduleStore {

    private const val PREFS = "henny_schedule"
    private const val KEY_JSON = "schedule"
    private const val KEY_FIRED = "lastFiredAt"

    data class Entry(val at: Long, val title: String, val body: String, val tag: String)

    fun save(context: Context, json: String) {
        prefs(context).edit().putString(KEY_JSON, json).apply()
    }

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

    fun lastFiredAt(context: Context): Long = prefs(context).getLong(KEY_FIRED, 0L)

    fun markFired(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_FIRED, at).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
