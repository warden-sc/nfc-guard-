package com.nfcguard.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.ArrayDeque

/**
 * 가속도계 + 자이로 값을 링버퍼로 상시 유지한다.
 * NFC 필드 이벤트가 오면 "새로 측정"하지 않고 이 버퍼를 바로 조회한다.
 * -> 판정 지연이 사실상 0에 수렴 (트랜잭션 타임아웃과 무관)
 */
data class MotionSample(
    val tNanos: Long,
    val ax: Float, val ay: Float, val az: Float,   // 선형 가속도 (중력 제외, m/s^2)
    val gx: Float, val gy: Float, val gz: Float    // 자이로 각속도 (rad/s)
)

class MotionBuffer(context: Context, private val windowMillis: Long = 2500L) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // 가장 최근 값을 임시 보관했다가 accel/gyro 둘 다 갱신되면 합쳐서 push
    @Volatile private var lastGx = 0f
    @Volatile private var lastGy = 0f
    @Volatile private var lastGz = 0f

    private val buffer = ArrayDeque<MotionSample>()
    private val lock = Any()

    fun start() {
        // GAME 레이트(~50Hz)면 걷기 주기(1.5~2.5Hz) 분석에 충분하면서 배터리 부담 적음
        sensorManager.registerListener(this, linearAccel, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
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

    /** NFC 필드 감지 시점 기준, 최근 windowMillis 구간 스냅샷을 즉시 반환 (블로킹 없음) */
    fun snapshot(): List<MotionSample> = synchronized(lock) { buffer.toList() }
}
