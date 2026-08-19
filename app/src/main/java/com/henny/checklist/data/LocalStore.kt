package com.henny.checklist.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 앱의 모든 상태는 작은 JSON 파일 몇 개로 저장한다.
 * DB를 쓰지 않기 때문에 APK가 가볍고, 그대로 원격 저장소에 올릴 수 있다.
 */
class LocalStore(context: Context) {

    private val dir: File = File(context.filesDir, "henny").apply { mkdirs() }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun file(name: String) = File(dir, name)

    private inline fun <reified T> read(name: String, fallback: T): T {
        val f = file(name)
        if (!f.exists()) return fallback
        return runCatching { json.decodeFromString<T>(f.readText()) }.getOrDefault(fallback)
    }

    private inline fun <reified T> write(name: String, value: T) {
        val f = file(name)
        val tmp = File(dir, "${name}.tmp")
        tmp.writeText(json.encodeToString(value))
        // 쓰는 중에 앱이 죽어도 반쪽짜리 파일이 남지 않도록 교체로 반영한다.
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun loadSettings(): Settings = read("settings.json", Settings())
    fun saveSettings(value: Settings) = write("settings.json", value)

    fun loadPlan(): Plan = read("plan.json", Plan())
    fun savePlan(value: Plan) = write("plan.json", value)

    fun loadProgress(childId: String): Progress =
        read("progress_$childId.json", Progress(childId = childId))

    fun saveProgress(value: Progress) = write("progress_${value.childId}.json", value)

    fun encode(plan: Plan): String = json.encodeToString(plan)
    fun encode(progress: Progress): String = json.encodeToString(progress)
    fun decodePlan(text: String): Plan = json.decodeFromString(text)
    fun decodeProgress(text: String): Progress = json.decodeFromString(text)
}
