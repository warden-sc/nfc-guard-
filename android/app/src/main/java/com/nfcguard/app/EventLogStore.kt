package com.nfcguard.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 메인 화면 "최근 감지 기록"용 로컬 저장소. 기기 안에만 최근 MAX_ENTRIES건 보관.
 * 원본 센서 시계열은 저장하지 않음(화면 표시용 요약 + 위치 기록). 위치는 서버로 보내지 않는다.
 */
object EventLogStore {

    const val PREFS = "nfcguard_event_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 30

    /** PROJECT_SCOPE 5절 locationStatus 값 */
    object LocationStatus {
        const val PENDING = "pending"             // 측정 중
        const val OK = "ok"                       // 정밀 위치 확보
        const val COARSE = "coarse"               // 대략적 위치만 허용됨
        const val OFF = "off"                     // 사용자가 위치 기록을 끔
        const val NO_PERMISSION = "no_permission" // 위치 권한 없음
        const val LOCATION_DISABLED = "location_disabled" // 기기 위치 서비스 꺼짐
        const val UNAVAILABLE = "unavailable"     // 시간 안에 위치를 얻지 못함 (실내·지하 등)
    }

    data class LocationRecord(
        val status: String,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val accuracyMeters: Float? = null,   // 수평 정확도 반경 (절대 오차 상한 아님)
        val measuredAtMs: Long? = null       // 위치가 실제 측정된 시각 (이벤트 시각과 구분)
    )

    data class Entry(
        val localId: String,
        val occurredAtMs: Long,
        val riskScore: Double,
        val reason: String,
        val alerted: Boolean,
        val eventId: Long?,       // 서버 업로드 성공 시 채워짐
        val label: Boolean?,      // true = 내가 태그함, false = 내가 안 함, null = 미확정
        val labelSynced: Boolean = false, // 라벨이 서버에 반영됐는지 (동의한 경우만 의미 있음)
        val location: LocationRecord? = null
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
    fun attachEventId(context: Context, localId: String, eventId: Long) =
        update(context) { if (it.localId == localId) it.copy(eventId = eventId) else it }

    @Synchronized
    fun attachLocation(context: Context, localId: String, location: LocationRecord) =
        update(context) { if (it.localId == localId) it.copy(location = location) else it }

    /** 화면/알림에서 사용자가 응답 -> 일단 기기에 저장, 서버 반영은 UploadQueue가 처리 */
    @Synchronized
    fun setLabelLocal(context: Context, localId: String, wasIntentional: Boolean) =
        update(context) { if (it.localId == localId) it.copy(label = wasIntentional, labelSynced = false) else it }

    /** 서버 confirm 성공 */
    @Synchronized
    fun markLabelSynced(context: Context, eventId: Long, wasIntentional: Boolean) =
        update(context) { if (it.eventId == eventId) it.copy(label = wasIntentional, labelSynced = true) else it }

    @Synchronized
    fun findByEventId(context: Context, eventId: Long): Entry? = read(context).firstOrNull { it.eventId == eventId }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    private fun update(context: Context, transform: (Entry) -> Entry) {
        write(context, read(context).map(transform))
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
                    label = if (o.has("label")) o.getBoolean("label") else null,
                    labelSynced = o.optBoolean("labelSynced"),
                    location = o.optJSONObject("location")?.let { l ->
                        LocationRecord(
                            status = l.getString("status"),
                            latitude = if (l.has("lat")) l.getDouble("lat") else null,
                            longitude = if (l.has("lon")) l.getDouble("lon") else null,
                            accuracyMeters = if (l.has("acc")) l.getDouble("acc").toFloat() else null,
                            measuredAtMs = if (l.has("at")) l.getLong("at") else null
                        )
                    }
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
                put("labelSynced", e.labelSynced)
                e.location?.let { l ->
                    put("location", JSONObject().apply {
                        put("status", l.status)
                        l.latitude?.let { put("lat", it) }
                        l.longitude?.let { put("lon", it) }
                        l.accuracyMeters?.let { put("acc", it.toDouble()) }
                        l.measuredAtMs?.let { put("at", it) }
                    })
                }
            })
        }
        prefs(context).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
