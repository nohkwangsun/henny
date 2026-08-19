package com.henny.checklist.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 서버 없이 작은 JSON 문서 몇 개만 주고받으면 되기 때문에
 * HttpURLConnection 하나로 끝낸다. (OkHttp/Retrofit 없음 = APK 가벼움)
 *
 * 문서 손잡이(handle)의 의미는 백엔드마다 다르다.
 *  - JSONBIN: bin id
 *  - HTTP   : 문서의 전체 URL
 */
class RemoteStore(private val backend: Backend, private val apiKey: String) {

    val configured: Boolean get() = backend != Backend.NONE

    suspend fun get(handle: String): String? = withContext(Dispatchers.IO) {
        when (backend) {
            Backend.NONE -> null
            Backend.JSONBIN -> request(
                url = "$JSONBIN/$handle/latest",
                method = "GET",
                body = null,
                headers = mapOf("X-Master-Key" to apiKey, "X-Bin-Meta" to "false")
            )
            Backend.HTTP -> request(handle, "GET", null, emptyMap())
            // Realtime Database 는 빈 경로에 리터럴 null 을 돌려준다. 없는 것으로 본다.
            Backend.FIREBASE -> request(withAuth(handle), "GET", null, emptyMap())
                ?.takeIf { it.trim() != "null" }
        }
    }

    suspend fun put(handle: String, body: String): Unit = withContext(Dispatchers.IO) {
        when (backend) {
            Backend.NONE -> Unit
            Backend.JSONBIN -> {
                request(
                    url = "$JSONBIN/$handle",
                    method = "PUT",
                    body = body,
                    headers = mapOf(
                        "X-Master-Key" to apiKey,
                        "X-Bin-Versioning" to "false"
                    )
                )
                Unit
            }
            Backend.HTTP -> {
                // jsonblob 계열은 PUT, npoint 계열은 POST 로 갱신한다.
                try {
                    request(handle, "PUT", body, emptyMap())
                } catch (e: MethodNotAllowed) {
                    request(handle, "POST", body, emptyMap())
                }
                Unit
            }
            Backend.FIREBASE -> {
                request(withAuth(handle), "PUT", body, emptyMap())
                Unit
            }
        }
    }

    /**
     * Realtime Database 는 인증 방식이 두 가지다.
     *  - 비밀키를 쓰는 경우: ?auth=<키>
     *  - 규칙으로 특정 경로만 열어둔 경우: 키 없이 그대로
     */
    private fun withAuth(handle: String): String =
        if (apiKey.isBlank()) handle
        else handle + (if ('?' in handle) "&" else "?") + "auth=" + apiKey

    /** 새 문서를 만들고 손잡이를 돌려준다. JSONBin 에서만 자동 생성이 가능하다. */
    suspend fun create(name: String, body: String): String = withContext(Dispatchers.IO) {
        when (backend) {
            Backend.JSONBIN -> {
                val res = request(
                    url = JSONBIN,
                    method = "POST",
                    body = body,
                    headers = mapOf(
                        "X-Master-Key" to apiKey,
                        "X-Bin-Name" to name,
                        "X-Bin-Private" to "true",
                        "X-Bin-Versioning" to "false"
                    )
                ) ?: throw IOException("저장소가 빈 응답을 보냈습니다.")
                parseBinId(res) ?: throw IOException("새 저장 공간의 id를 찾지 못했습니다.")
            }
            else -> throw IOException("이 백엔드는 저장 공간 자동 생성을 지원하지 않습니다.")
        }
    }

    private class MethodNotAllowed : IOException("PUT을 받지 않는 주소입니다.")

    private fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>
    ): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val code = conn.responseCode
            if (code == 404) return null
            if (code == 405) throw MethodNotAllowed()
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) throw IOException(friendly(code, text))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(code: Int, body: String): String = when (code) {
        401, 403 -> "저장소 열쇠(API Key)가 맞지 않습니다. (HTTP $code)"
        429 -> "저장소 호출 한도를 넘었습니다. 잠시 뒤 다시 시도하세요."
        in 500..599 -> "저장소 서버에 문제가 있습니다. (HTTP $code)"
        else -> "저장소 오류 HTTP $code ${body.take(120)}"
    }

    /**
     * {"record":{...},"metadata":{"id":"..."}} 에서 새 bin 의 id 만 뽑는다.
     * record 안에도 "id" 가 들어 있을 수 있으므로 반드시 metadata 뒤에서 찾는다.
     */
    private fun parseBinId(res: String): String? {
        val from = res.indexOf("\"metadata\"").let { if (it < 0) 0 else it }
        val marker = "\"id\""
        var idx = res.indexOf(marker, from)
        while (idx >= 0) {
            val colon = res.indexOf(':', idx + marker.length)
            if (colon < 0) return null
            val start = res.indexOf('"', colon + 1)
            val end = if (start >= 0) res.indexOf('"', start + 1) else -1
            if (start >= 0 && end > start) {
                val value = res.substring(start + 1, end)
                if (value.isNotBlank()) return value
            }
            idx = res.indexOf(marker, idx + 1)
        }
        return null
    }

    companion object {
        const val JSONBIN = "https://api.jsonbin.io/v3/b"
    }
}
