package com.nfcguard.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 서버 전송 대기열 (파일 기반, 앱 재시작에도 유지).
 * 지하철 등 네트워크가 끊긴 곳에서 감지된 이벤트·라벨이 유실되지 않도록 먼저 저장하고 보낸다.
 * 데이터 제공에 동의한 경우에만 쌓이며, 동의를 철회하면 대기 중인 항목도 지운다.
 *
 * 전송 시점: 새 항목 추가 직후, 네트워크 연결 시(NfcGuardApp), 메인 화면 진입 시.
 */
object UploadQueue {

    private const val TAG = "UploadQueue"
    private const val DIR = "pending_uploads"
    private const val MAX_PENDING = 50
    private const val SEP = "__"

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * @param localId EventLogStore 항목과 연결할 id (기록에 남지 않는 정상/온보딩 샘플은 null)
     */
    fun enqueue(context: Context, localId: String?, payload: JSONObject) {
        val app = context.applicationContext
        if (!PrivacySettings.canUpload(app)) return
        executor.execute {
            val id = localId ?: "anon-${UUID.randomUUID()}"
            File(dir(app), "${System.currentTimeMillis()}$SEP$id.json").writeText(payload.toString())
            trim(app)
            flushNow(app)
        }
    }

    fun flushAsync(context: Context) {
        val app = context.applicationContext
        executor.execute { flushNow(app) }
    }

    /** 동의 철회 / 데이터 삭제 시 */
    fun clearPendingAsync(context: Context) {
        val app = context.applicationContext
        executor.execute { dir(app).listFiles()?.forEach { it.delete() } }
    }

    private fun flushNow(context: Context) {
        if (!PrivacySettings.canUpload(context)) {
            dir(context).listFiles()?.forEach { it.delete() }
            return
        }
        for (file in pendingFiles(context)) {
            val id = file.name.substringAfter(SEP).removeSuffix(".json")
            val payload = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (payload == null) {
                file.delete()
                continue
            }
            when (val result = BackendClient.postEvent(context, payload)) {
                is BackendClient.UploadResult.Uploaded -> {
                    file.delete()
                    if (!id.startsWith("anon-")) EventLogStore.attachEventId(context, id, result.eventId)
                }
                BackendClient.UploadResult.Drop -> {
                    Log.w(TAG, "server rejected event $id - dropped")
                    file.delete()
                }
                BackendClient.UploadResult.Retry -> return // 네트워크 문제: 순서 유지하고 다음 기회에
            }
        }
        syncLabels(context)
    }

    /** 사용자가 응답한 라벨 중 서버에 아직 반영 안 된 것 (이벤트가 업로드된 뒤에만 가능) */
    private fun syncLabels(context: Context) {
        EventLogStore.list(context)
            .filter { it.eventId != null && it.label != null && !it.labelSynced }
            .forEach { entry ->
                val label = entry.label ?: return@forEach
                if (BackendClient.confirmLabel(context, entry.eventId!!, label)) {
                    EventLogStore.markLabelSynced(context, entry.eventId, label)
                } else {
                    return
                }
            }
    }

    private fun trim(context: Context) {
        val files = pendingFiles(context)
        if (files.size > MAX_PENDING) files.take(files.size - MAX_PENDING).forEach { it.delete() } // 오래된 것부터
    }

    private fun pendingFiles(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }
}
