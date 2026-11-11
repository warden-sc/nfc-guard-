package com.nfcguard.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager

/**
 * "기기가 가방/주머니 안에 있는가"를 판단하기 위한 보조 신호.
 * 손동작(MotionBuffer)만으로는 "가만히 있는 폰에 리더기를 갖다 댄 경우"와
 * "사용자가 폰을 아주 조용히 갖다 댄 경우"를 구분하기 어렵기 때문에,
 * 근접센서(가려짐) + 조도(어두움) + 화면 상태를 함께 본다.
 *
 * 근접/조도는 on-change 센서라 배터리 부담이 작다.
 */
data class DeviceContext(
    val proximityNear: Boolean?, // null = 센서 없음/아직 값 없음
    val ambientLux: Float?,      // null = 센서 없음/아직 값 없음
    val screenInteractive: Boolean
)

class DeviceContextMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    @Volatile private var proximityNear: Boolean? = null
    @Volatile private var ambientLux: Float? = null
    private var started = false

    /** 여러 곳(Application, Foreground Service)에서 불러도 한 번만 등록 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        proximity?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        light?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    @Synchronized
    fun stop() {
        sensorManager.unregisterListener(this)
        started = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            // 대부분 기종은 near/far 이진값만 주므로 maximumRange 미만이면 가려진 것으로 본다
            Sensor.TYPE_PROXIMITY -> proximityNear = event.values[0] < event.sensor.maximumRange
            Sensor.TYPE_LIGHT -> ambientLux = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** NFC 필드 감지 시점에 즉시 조회 (블로킹 없음) */
    fun snapshot(): DeviceContext = DeviceContext(
        proximityNear = proximityNear,
        ambientLux = ambientLux,
        screenInteractive = powerManager.isInteractive
    )
}
