package com.nfcguard.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * server-java 와 통신. 모든 호출은 블로킹이므로 반드시 백그라운드 스레드(AnomalyReporter.executor 등)에서 호출.
 * 외부 라이브러리 없이 HttpURLConnection + org.json(안드로이드 내장)만 사용.
 *
 * 실배포 시: HTTPS 필수. 서버가 Play Integrity 검증을 켜면(integrityRequired) 등록 시 토큰을 첨부한다.
 * 서버 전송은 사용자가 데이터 제공에 동의한 경우에만 (PrivacySettings) - 호출부와 여기서 이중 확인.
 */
object BackendClient {

    private const val PREFS = "nfcguard_backend"
    private const val TIMEOUT_MS = 5_000
    private const val MAX_METADATA_BYTES = 64 * 1024
    private const val MAX_MODEL_BYTES = 20 * 1024 * 1024

    private data class Credentials(val deviceToken: String, val apiKey: String)

    /** 업로드 결과: 재시도할지 버릴지 구분해야 오프라인 큐가 영원히 같은 항목을 붙잡지 않는다 */
    sealed class UploadResult {
        data class Uploaded(val eventId: Long) : UploadResult()
        /** 네트워크 끊김·서버 오류 -> 나중에 다시 */
        object Retry : UploadResult()
        /** 서버가 형식을 거절(4xx)했거나 동의 철회 -> 다시 보내도 소용없음 */
        object Drop : UploadResult()
    }

    fun postEvent(context: Context, payload: JSONObject): UploadResult {
        if (!PrivacySettings.canUpload(context)) return UploadResult.Drop
        val creds = credentials(context) ?: return UploadResult.Retry
        val (code, body) = request("POST", "/api/v1/events", payload.toString(), creds)
        return when {
            code == 200 -> body?.let { JSONObject(it).optLong("id").takeIf { id -> id > 0 } }
                ?.let { UploadResult.Uploaded(it) } ?: UploadResult.Retry
            code == 401 -> {
                clearCredentials(context) // 서버 DB 초기화 등 -> 다음 시도에서 재등록
                UploadResult.Retry
            }
            code == 429 || code < 0 || code >= 500 -> UploadResult.Retry
            else -> UploadResult.Drop
        }
    }

    fun confirmLabel(context: Context, eventId: Long, wasIntentional: Boolean): Boolean {
        if (!PrivacySettings.canUpload(context)) return false
        val creds = credentials(context) ?: return false
        val (code, _) = request("POST", "/api/v1/events/$eventId/confirm?wasIntentional=$wasIntentional", null, creds)
        return code == 200
    }

    /**
     * "내 데이터 삭제": 서버에 있는 이 기기의 이벤트·라벨·등록 정보를 삭제하고 로컬 자격 증명도 지운다.
     * 등록한 적이 없으면 지울 것도 없으므로 성공으로 본다.
     */
    fun deleteMyServerData(context: Context): Boolean {
        val creds = storedCredentials(context) ?: return true
        val (code, _) = request("DELETE", "/api/v1/devices/me", null, creds)
        if (code == 200 || code == 401) {
            clearCredentials(context)
            return true
        }
        return false
    }

    /** @return (메타데이터 원문 바이트, X-Model-Signature 헤더). 모델이 아직 없으면 null */
    fun fetchModelMetadata(): Pair<ByteArray, String?>? = download("/api/v1/model/metadata", MAX_METADATA_BYTES)

    fun downloadModel(): ByteArray? = download("/api/v1/model/latest", MAX_MODEL_BYTES)?.first

    private fun download(path: String, maxBytes: Int): Pair<ByteArray, String?>? = try {
        val conn = URL(BuildConfig.BACKEND_URL + path).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) null
            else {
                val bytes = conn.inputStream.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > maxBytes) throw IllegalStateException("$path exceeds $maxBytes bytes")
                    }
                    buf.toByteArray()
                }
                bytes to conn.getHeaderField("X-Model-Signature")
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET $path failed: ${e.message}"); null
    }

    /** MotionBuffer 스냅샷 -> ml-python/data_loader.py 가 읽는 형식 ([{"t","ax",...,"gz"}]) */
    fun sensorWindowJson(samples: List<MotionSample>): JSONArray = JSONArray().apply {
        samples.forEach { s ->
            put(JSONObject().apply {
                put("t", s.tNanos)
                put("ax", s.ax.toDouble()); put("ay", s.ay.toDouble()); put("az", s.az.toDouble())
                put("gx", s.gx.toDouble()); put("gy", s.gy.toDouble()); put("gz", s.gz.toDouble())
            })
        }
    }

    private fun storedCredentials(context: Context): Credentials? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("deviceToken", null)
        val key = prefs.getString("apiKey", null)
        return if (token != null && key != null) Credentials(token, key) else null
    }

    @Synchronized
    private fun credentials(context: Context): Credentials? {
        storedCredentials(context)?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 서버가 발급한 nonce로 Play Integrity 토큰을 받아 등록 요청에 첨부 (서버 설정이 off면 생략)
        val (nonceCode, nonceBody) = request("GET", "/api/v1/devices/nonce", null, null)
        if (nonceCode != 200 || nonceBody == null) return null
        val nonceJson = JSONObject(nonceBody)
        val registerBody = JSONObject().put("nonce", nonceJson.getString("nonce"))
        if (nonceJson.optBoolean("integrityRequired")) {
            val token = PlayIntegrity.requestToken(context, nonceJson.getString("nonce")) ?: return null
            registerBody.put("integrityToken", token)
        }

        val (code, body) = request("POST", "/api/v1/devices/register", registerBody.toString(), null)
        if (code != 200 || body == null) return null
        val json = JSONObject(body)
        val creds = Credentials(json.getString("deviceToken"), json.getString("apiKey"))
        prefs.edit().putString("deviceToken", creds.deviceToken).putString("apiKey", creds.apiKey).apply()
        return creds
    }

    @Synchronized
    private fun clearCredentials(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun request(method: String, path: String, jsonBody: String?, creds: Credentials?): Pair<Int, String?> {
        return try {
            val conn = URL(BuildConfig.BACKEND_URL + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            creds?.let {
                conn.setRequestProperty("X-Device-Token", it.deviceToken)
                conn.setRequestProperty("Authorization", "Bearer ${it.apiKey}")
            }
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
            conn.disconnect()
            code to body
        } catch (e: Exception) {
            Log.w(TAG, "$method $path failed: ${e.message}")
            -1 to null
        }
    }

    private const val TAG = "BackendClient"
}
