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
 * 실배포 시: HTTPS 필수 + 인증서 피닝 권장, 기기 등록에는 Play Integrity 검증 추가 권장.
 */
object BackendClient {

    private const val PREFS = "nfcguard_backend"
    private const val TIMEOUT_MS = 5_000

    private data class Credentials(val deviceToken: String, val apiKey: String)

    /** @return 서버 이벤트 id (실패 시 null) */
    fun postEvent(context: Context, payload: JSONObject): Long? {
        val creds = credentials(context) ?: return null
        val (code, body) = request("POST", "/api/v1/events", payload.toString(), creds)
        if (code == 401) clearCredentials(context) // 서버 DB 초기화 등 -> 다음 호출에서 재등록
        return if (code == 200) body?.let { JSONObject(it).optLong("id").takeIf { id -> id > 0 } } else null
    }

    fun confirmLabel(context: Context, eventId: Long, wasIntentional: Boolean): Boolean {
        val creds = credentials(context) ?: return false
        val (code, _) = request("POST", "/api/v1/events/$eventId/confirm?wasIntentional=$wasIntentional", null, creds)
        return code == 200
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

    @Synchronized
    private fun credentials(context: Context): Credentials? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("deviceToken", null)
        val key = prefs.getString("apiKey", null)
        if (token != null && key != null) return Credentials(token, key)

        val (code, body) = request("POST", "/api/v1/devices/register", null, null)
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
