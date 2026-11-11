package com.nfcguard.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 서버의 최신 파인튜닝 모델을 받아 검증 후 교체한다.
 * 순서: metadata(+서명) 검증 -> 버전 비교 -> tflite 다운로드 -> sha256 검증 -> 원자적 교체 -> 로드.
 * 어느 단계든 실패하면 기존 모델(또는 휴리스틱 단독)을 그대로 유지한다 - 탐지가 꺼지는 일은 없음.
 */
object ModelUpdater {

    private const val TAG = "ModelUpdater"
    private const val MODEL_FILE = "gesture_classifier.tflite"
    private const val METADATA_FILE = "gesture_classifier.json"
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val periodicScheduled = AtomicBoolean(false)

    @Volatile var current: TfliteGestureClassifier? = null
        private set

    /** 앱 시작 시: 이미 설치된 모델이 있으면 로드 */
    fun loadInstalled(context: Context) {
        scheduler.execute {
            val dir = modelDir(context)
            val meta = File(dir, METADATA_FILE)
            val model = File(dir, MODEL_FILE)
            if (!meta.exists() || !model.exists()) return@execute
            val spec = parseSpec(meta.readBytes()) ?: return@execute
            if (ModelSignatureVerifier.sha256Hex(model.readBytes()) != spec.sha256) return@execute
            install(model, spec)
        }
    }

    /** Foreground Service가 재시작돼도 한 번만 등록 */
    fun schedulePeriodicCheck(context: Context) {
        if (!periodicScheduled.compareAndSet(false, true)) return
        val app = context.applicationContext
        scheduler.scheduleWithFixedDelay({ runCatching { checkAndUpdate(app) } }, 1, 24 * 60, TimeUnit.MINUTES)
    }

    private fun checkAndUpdate(context: Context) {
        val (metaBytes, signature) = BackendClient.fetchModelMetadata() ?: return

        val publicKey = BuildConfig.MODEL_SIGNING_PUBLIC_KEY
        if (publicKey.isNotBlank()) {
            if (!ModelSignatureVerifier.verify(metaBytes, signature, publicKey)) {
                Log.w(TAG, "model metadata signature invalid - rejected")
                return
            }
        } else {
            Log.w(TAG, "MODEL_SIGNING_PUBLIC_KEY 미설정 - 서명 검증 없이 sha256만 확인 (개발용)")
        }

        val spec = parseSpec(metaBytes) ?: return
        if (!spec.isSupported()) {
            Log.w(TAG, "unsupported model spec: $spec")
            return
        }
        if (spec.version <= (current?.spec?.version ?: 0L)) return

        val modelBytes = BackendClient.downloadModel() ?: return
        if (ModelSignatureVerifier.sha256Hex(modelBytes) != spec.sha256) {
            Log.w(TAG, "model sha256 mismatch - rejected")
            return
        }

        val dir = modelDir(context)
        val model = File(dir, MODEL_FILE)
        writeAtomically(File(dir, "$MODEL_FILE.tmp"), modelBytes, model)
        writeAtomically(File(dir, "$METADATA_FILE.tmp"), metaBytes, File(dir, METADATA_FILE))
        install(model, spec)
    }

    private fun install(model: File, spec: GestureModelSpec) {
        try {
            val old = current
            current = TfliteGestureClassifier(model, spec)
            old?.close()
            Log.i(TAG, "gesture model v${spec.version} loaded")
        } catch (e: Exception) {
            Log.w(TAG, "model load failed - keep previous", e)
        }
    }

    private fun parseSpec(bytes: ByteArray): GestureModelSpec? = try {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val input = json.getJSONObject("input")
        val features = input.getJSONArray("features")
        GestureModelSpec(
            version = json.getLong("version"),
            sha256 = json.getString("sha256"),
            windowLen = input.getInt("window_len"),
            features = (0 until features.length()).map { features.getString(it) },
            resample = input.getString("resample"),
            threshold = json.getDouble("threshold"),
            blendWeight = json.optDouble("blend_weight", 0.5)
        )
    } catch (e: Exception) {
        Log.w(TAG, "invalid model metadata", e); null
    }

    private fun writeAtomically(tmp: File, bytes: ByteArray, target: File) {
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun modelDir(context: Context) = File(context.filesDir, "model").apply { mkdirs() }
}
