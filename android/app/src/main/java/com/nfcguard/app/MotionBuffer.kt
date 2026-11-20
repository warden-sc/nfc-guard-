package com.nfcguard.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/**
 * 가속도계 + 자이로 값을 링버퍼로 상시 유지한다.
 * NFC 필드 이벤트가 오면 "새로 측정"하지 않고 이 버퍼를 바로 조회한다.
 * -> 판정 지연이 사실상 0에 수렴 (트랜잭션 타임아웃과 무관)
 *
 * 화면이 꺼져 CPU가 절전(suspend)에 들어가면 non-wakeup 센서 이벤트는 앱으로 오지 않고
 * 센서 허브 하드웨어 FIFO에 쌓인다(가득 차면 오래된 것부터 덮어씀 = 항상 "최근 값"이 남음).
 * 가방 안 시나리오가 바로 이 상태이므로, 필드 감지 시 flushThen()으로 FIFO를 끌어온 뒤 판정한다.
 */
data class MotionSample(
    val tNanos: Long,
    val ax: Float, val ay: Float, val az: Float,   // 선형 가속도 (중력 제외, m/s^2)
    val gx: Float, val gy: Float, val gz: Float    // 자이로 각속도 (rad/s)
)

class MotionBuffer(context: Context, private val windowMillis: Long = 2500L) : SensorEventListener2 {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // 가장 최근 값을 임시 보관했다가 accel/gyro 둘 다 갱신되면 합쳐서 push
    @Volatile private var lastGx = 0f
    @Volatile private var lastGy = 0f
    @Volatile private var lastGz = 0f

    private val buffer = ArrayDeque<MotionSample>()
    private val lock = Any()
    @Volatile private var started = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingFlushCallbacks = mutableListOf<() -> Unit>()

    /** 여러 곳(Application, Foreground Service)에서 불러도 한 번만 등록 */
    fun start() {
        synchronized(lock) {
            if (started) return
            // GAME 레이트(~50Hz)면 걷기 주기(1.5~2.5Hz) 분석에 충분하면서 배터리 부담 적음
            sensorManager.registerListener(this, linearAccel, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
            started = true
        }
    }

    fun stop() {
        synchronized(lock) {
            sensorManager.unregisterListener(this)
            started = false
        }
    }

    /**
     * 하드웨어 FIFO가 2.5초 창(약 50Hz x 2.5s)을 담을 수 있는지.
     * 부족하면 화면 꺼짐 중 데이터가 끊기므로 호출부(MotionForegroundService)가 wake lock으로 보완한다.
     */
    fun hasSufficientFifo(): Boolean {
        val needed = (windowMillis / 20L).toInt() // 50Hz 기준 샘플 수
        val accelFifo = linearAccel?.fifoMaxEventCount ?: 0
        return accelFifo >= needed
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                lastGx = event.values[0]; lastGy = event.values[1]; lastGz = event.values[2]
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val sample = MotionSample(
                    tNanos = event.timestamp,
                    ax = event.values[0], ay = event.values[1], az = event.values[2],
                    gx = lastGx, gy = lastGy, gz = lastGz
                )
                synchronized(lock) {
                    buffer.addLast(sample)
                    val cutoff = event.timestamp - windowMillis * 1_000_000L
                    while (buffer.isNotEmpty() && buffer.first.tNanos < cutoff) {
                        buffer.removeFirst()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onFlushCompleted(sensor: Sensor) {
        // 가속도 flush가 끝나면 버퍼가 최신 상태 (자이로는 lastG* 갱신용이라 기다리지 않음)
        if (sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) mainHandler.post { runPendingFlushCallbacks() }
    }

    /** NFC 필드 감지 시점 기준, 최근 windowMillis 구간 스냅샷을 즉시 반환 (블로킹 없음) */
    fun snapshot(): List<MotionSample> = synchronized(lock) { buffer.toList() }

    /**
     * 하드웨어 FIFO에 쌓인 이벤트를 모두 받아온 뒤 메인 스레드에서 callback 실행.
     * flush 미지원/지연 시에도 FLUSH_TIMEOUT_MS 후에는 반드시 실행된다 (판정 누락 방지).
     */
    fun flushThen(callback: () -> Unit) {
        synchronized(pendingFlushCallbacks) { pendingFlushCallbacks.add(callback) }
        val flushRequested = started && sensorManager.flush(this)
        if (flushRequested) {
            mainHandler.postDelayed({ runPendingFlushCallbacks() }, FLUSH_TIMEOUT_MS)
        } else {
            mainHandler.post { runPendingFlushCallbacks() }
        }
    }

    private fun runPendingFlushCallbacks() {
        val callbacks = synchronized(pendingFlushCallbacks) {
            pendingFlushCallbacks.toList().also { pendingFlushCallbacks.clear() }
        }
        callbacks.forEach { it() }
    }

    companion object {
        private const val FLUSH_TIMEOUT_MS = 150L
    }
}
