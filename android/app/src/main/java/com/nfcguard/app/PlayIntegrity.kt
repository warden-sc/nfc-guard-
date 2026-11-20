package com.nfcguard.app

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.util.concurrent.TimeUnit

/**
 * 기기 등록 시 Play Integrity 토큰 요청 (서버 nfcguard.integrity.mode=google 일 때만 호출됨).
 * 플레이스토어 정식 설치·정상 기기인지 서버가 확인해 가짜 기기 대량 등록(학습 데이터 오염)을 막는다.
 * 블로킹 호출이므로 백그라운드 스레드에서만.
 */
object PlayIntegrity {

    fun requestToken(context: Context, nonce: String): String? = try {
        val builder = IntegrityTokenRequest.builder().setNonce(nonce)
        // Play 배포 앱은 생략 가능, 그 외에는 Play Console에 연결한 Google Cloud 프로젝트 번호 필요
        if (BuildConfig.PLAY_CLOUD_PROJECT_NUMBER > 0) builder.setCloudProjectNumber(BuildConfig.PLAY_CLOUD_PROJECT_NUMBER)
        val task = IntegrityManagerFactory.create(context.applicationContext).requestIntegrityToken(builder.build())
        Tasks.await(task, 20, TimeUnit.SECONDS).token()
    } catch (e: Exception) {
        Log.w("PlayIntegrity", "integrity token request failed: ${e.message}")
        null
    }
}
