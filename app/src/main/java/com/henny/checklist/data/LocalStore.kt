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

    @PublishedApi
    internal val dir: File = File(context.filesDir, "henny").apply { mkdirs() }

    @PublishedApi
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @PublishedApi
    internal fun file(name: String) = File(dir, name)

    private inline fun <reified T> read(name: String, fallback: T): T {
        val f = file(name)
        if (!f.exists()) return fallback
        val text = f.readText()
        return runCatching { json.decodeFromString<T>(text) }.getOrElse {
            // 저장 형식이 바뀌어 못 읽는 경우가 있다. 조용히 버리면 복구할 길이 없으므로
            // 원본을 옆에 남겨 두고 기본값으로 시작한다. (팀 저장소를 쓰면 곧 다시 받아온다)
            runCatching { File(dir, "$name.broken").writeText(text) }
            fallback
        }
    }

    /** 형식이 안 맞아 밀려난 파일이 있는지. 설정 화면에서 알려 주려고 쓴다. */
    fun brokenFiles(): List<String> =
        dir.listFiles()?.filter { it.name.endsWith(".broken") }?.map { it.name }.orEmpty()

    fun clearBrokenFiles() {
        dir.listFiles()?.filter { it.name.endsWith(".broken") }?.forEach { it.delete() }
    }

    /** 연결 코드처럼 작은 객체를 주고받을 때 쓰는 공용 인코더. */
    inline fun <reified T> encodeAny(value: T): String = json.encodeToString(value)
    inline fun <reified T> decodeAny(text: String): T = json.decodeFromString(text)

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

    fun loadProgress(workerId: String): Progress =
        read("progress_$workerId.json", Progress(workerId = workerId))

    fun saveProgress(value: Progress) = write("progress_${value.workerId}.json", value)

    fun encode(plan: Plan): String = json.encodeToString(plan)
    fun encode(progress: Progress): String = json.encodeToString(progress)
    fun decodePlan(text: String): Plan = json.decodeFromString(text)
    fun decodeProgress(text: String): Progress = json.decodeFromString(text)
}
