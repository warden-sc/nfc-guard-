package com.nfcguard.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 메인 화면 "최근 감지 기록"용 로컬 저장소. 서버와 별개로 기기 안에만 최근 MAX_ENTRIES건 보관.
 * (원본 센서 시계열은 저장하지 않음 - 화면 표시에 필요한 요약만)
 */
object EventLogStore {

    const val PREFS = "nfcguard_event_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 30

    data class Entry(
        val localId: String,
        val occurredAtMs: Long,
        val riskScore: Double,
        val reason: String,
        val alerted: Boolean,
        val eventId: Long?,  // 서버 업로드 성공 시 채워짐 (라벨 버튼은 이게 있어야 동작)
        val label: Boolean?  // true = 내가 태그함, false = 내가 안 함, null = 미확정
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 최신순 */
    @Synchronized
    fun list(context: Context): List<Entry> = read(context)

    @Synchronized
    fun add(context: Context, entry: Entry) {
        write(context, (listOf(entry) + read(context)).take(MAX_ENTRIES))
    }

    @Synchronized
    fun attachEventId(context: Context, localId: String, eventId: Long) {
        write(context, read(context).map { if (it.localId == localId) it.copy(eventId = eventId) else it })
    }

    @Synchronized
    fun setLabel(context: Context, eventId: Long, wasIntentional: Boolean) {
        write(context, read(context).map { if (it.eventId == eventId) it.copy(label = wasIntentional) else it })
    }

    private fun read(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    localId = o.getString("localId"),
                    occurredAtMs = o.getLong("occurredAtMs"),
                    riskScore = o.getDouble("riskScore"),
                    reason = o.getString("reason"),
                    alerted = o.optBoolean("alerted"),
                    eventId = if (o.has("eventId")) o.getLong("eventId") else null,
                    label = if (o.has("label")) o.getBoolean("label") else null
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("localId", e.localId)
                put("occurredAtMs", e.occurredAtMs)
                put("riskScore", e.riskScore)
                put("reason", e.reason)
                put("alerted", e.alerted)
                e.eventId?.let { put("eventId", it) }
                e.label?.let { put("label", it) }
            })
        }
        prefs(context).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
