package com.ninezero.core.fcm

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.ninezero.core.common.util.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BatchPushResult(
    val successCount: Int,
    val failureCount: Int,
    val invalidFids: List<String> = emptyList()
)

class FcmService {
    private val logger = logger()

    /** 500개 단위 일괄 발송 */
    suspend fun sendBatchPush(
        fids: List<String>,
        title: String,
        body: String,
        data: Map<String, String>? = null
    ): BatchPushResult {
        if (!FcmConfig.isAvailable()) {
            logger.debug("Firebase를 사용할 수 없어 멀티캐스트 푸시 알림 전송을 건너뜁니다")
            return BatchPushResult(successCount = 0, failureCount = fids.size)
        }

        if (fids.isEmpty()) {
            logger.debug("FCM 토큰이 없어 멀티캐스트 푸시 알림 전송을 건너뜁니다")
            return BatchPushResult(successCount = 0, failureCount = 0)
        }

        return try {
            var totalSuccessCount = 0
            var totalFailureCount = 0
            val invalidFids = mutableSetOf<String>()

            // FCM은 한 번에 최대 500개의 토큰까지 지원
            fids.chunked(500).forEach { tokenBatch ->
                val messageBuilder = MulticastMessage.builder()
                    .addAllFids(tokenBatch)

                messageBuilder.putData("title", title)
                messageBuilder.putData("message", body)
                data?.let { messageBuilder.putAllData(it) }

                messageBuilder.setAndroidConfig(androidConfigFor(data))

                val message = messageBuilder.build()

                val batchResponse = withContext(Dispatchers.IO) {
                    FirebaseMessaging.getInstance().sendEachForMulticast(message)
                }

                totalSuccessCount += batchResponse.successCount
                totalFailureCount += batchResponse.failureCount

                logger.info(
                    "멀티캐스트 푸시 알림 전송 완료: 성공 {}건, 실패 {}건, 대상 토큰 {}개",
                    batchResponse.successCount,
                    batchResponse.failureCount,
                    tokenBatch.size
                )

                if (batchResponse.failureCount > 0) {
                    batchResponse.responses.forEachIndexed { index, sendResponse ->
                        if (!sendResponse.isSuccessful) {
                            val token = tokenBatch.getOrNull(index)
                            if (token != null && isInvalidTokenException(sendResponse.exception)) {
                                invalidFids += token
                            }
                            logger.warn(
                                "토큰 전송 실패: token={}, error={}",
                                token?.take(10) + "...",
                                sendResponse.exception?.message
                            )
                        }
                    }
                }
            }

            logger.info("멀티캐스트 푸시 알림 전체 전송 완료: 성공 {}건 / 전체 {}건", totalSuccessCount, fids.size)
            BatchPushResult(
                successCount = totalSuccessCount,
                failureCount = totalFailureCount,
                invalidFids = invalidFids.toList()
            )

        } catch (e: Exception) {
            logger.error("멀티캐스트 푸시 알림 전송 실패: {}", e.message)
            BatchPushResult(successCount = 0, failureCount = fids.size)
        }
    }

    private fun androidConfigFor(data: Map<String, String>?): AndroidConfig {
        val type = data?.get("type").orEmpty()
        val timeSensitive = type.startsWith("CHAT") ||
                type.startsWith("ORDER") ||
                type.startsWith("SHIPPING")
        val priority = if (timeSensitive) AndroidConfig.Priority.HIGH else AndroidConfig.Priority.NORMAL  // 소셜은 NORMAL
        return AndroidConfig.builder().setPriority(priority).build()
    }

    private fun isInvalidTokenException(exception: Exception?): Boolean {
        val fcmException = exception as? FirebaseMessagingException
        val messagingErrorCode = fcmException?.messagingErrorCode

        if (messagingErrorCode == MessagingErrorCode.UNREGISTERED || messagingErrorCode == MessagingErrorCode.INVALID_ARGUMENT) {
            return true
        }

        // 폴백: 404 문구로 무효 토큰 판정
        val message = exception?.message ?: return false
        return message.contains("requested entity was not found", ignoreCase = true)
    }
}
