package com.nfcguard.app

import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * ml-python/finetune.py 산출 TFLite 모델 실행기.
 * 입력: [1][windowLen][features] float (원시 센서값 - 정규화는 모델 안에 포함), 출력: [1][1] p(의도적 태그)
 */
class TfliteGestureClassifier(modelFile: File, val spec: GestureModelSpec) : AutoCloseable {

    private val interpreter = Interpreter(modelFile, Interpreter.Options().setNumThreads(1))

    @Synchronized
    fun predictIntentionalProb(samples: List<MotionSample>): Double {
        val input = arrayOf(SensorPreprocessor.resample(samples, spec.windowLen, spec.features))
        val output = arrayOf(FloatArray(1))
        interpreter.run(input, output)
        return output[0][0].toDouble()
    }

    @Synchronized
    override fun close() = interpreter.close()
}
