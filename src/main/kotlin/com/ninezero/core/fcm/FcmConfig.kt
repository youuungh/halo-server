package com.ninezero.core.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.logger
import java.io.FileInputStream

object FcmConfig {
    private val logger = logger()
    private var isInitialized = false

    fun initialize() {
        if (isInitialized) {
            logger.debug("Firebase가 이미 초기화되어 있습니다")
            return
        }

        try {
            val jsonContent = DotenvConfig["FIREBASE_CREDENTIALS_JSON"]

            val credentials = if (!jsonContent.isNullOrBlank()) {
                GoogleCredentials.fromStream(jsonContent.byteInputStream())
            } else {
                val serviceAccountPath = DotenvConfig.getOrDefault(
                    "FIREBASE_SERVICE_ACCOUNT_KEY",
                    "serviceAccountKey.json"
                )

                logger.debug("파일에서 Firebase 인증 정보를 불러옵니다")

                val stream = try {
                    FileInputStream(serviceAccountPath)
                } catch (_: Exception) {
                    logger.debug("파일을 찾지 못해 classpath에서 다시 시도합니다")
                    this::class.java.classLoader.getResourceAsStream(serviceAccountPath)
                        ?: throw RuntimeException("Firebase credentials not found")
                }

                GoogleCredentials.fromStream(stream)
            }

            val options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build()

            FirebaseApp.initializeApp(options)
            isInitialized = true
            logger.info("Firebase 초기화 완료")
        } catch (e: Exception) {
            logger.warn("Firebase 초기화 실패: ${e.message}. FCM 푸시 알림이 비활성화됩니다")
            logger.debug("Firebase 초기화 오류 상세", e)
        }
    }

    fun isAvailable(): Boolean = isInitialized
}
