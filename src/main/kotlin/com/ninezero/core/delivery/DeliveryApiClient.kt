package com.ninezero.core.delivery

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

@Serializable
private data class DeliveryTraceRequest(
    val items: List<DeliveryTraceItemRequest>
)

@Serializable
private data class DeliveryTraceItemRequest(
    val courierCode: String,
    val trackingNumber: String,
    val clientId: String? = null
)

@Serializable
private data class DeliveryTraceResponse(
    val isSuccess: Boolean,
    val data: DeliveryTraceData? = null
)

@Serializable
private data class DeliveryTraceData(
    val results: List<DeliveryTraceResult> = emptyList()
)

@Serializable
private data class DeliveryRegisterRequest(
    val items: List<DeliveryTraceItemRequest>,
    val recurring: Boolean,
    val endpointId: String
)

@Serializable
private data class DeliveryRegisterResponse(
    val isSuccess: Boolean,
    val data: DeliveryRegisterData? = null
)

@Serializable
private data class DeliveryRegisterData(
    val requestId: String? = null
)

@Serializable
private data class DeliverySubscriptionDetailResponse(
    val isSuccess: Boolean,
    val data: DeliverySubscriptionDetail? = null
)

@Serializable
private data class DeliverySubscriptionDetail(
    val items: List<DeliveryTrackingItem> = emptyList()
)

@Serializable
data class DeliveryTraceResult(
    val clientId: String? = null,
    val success: Boolean = false,
    val data: DeliveryTracePayload? = null,
    val error: DeliveryTraceError? = null
)

@Serializable
data class DeliveryTracePayload(
    val trackingNumber: String? = null,
    val courierCode: String? = null,
    val courierName: String? = null,
    val deliveryStatus: String? = null,
    val deliveryStatusText: String? = null,
    val dateLastProgress: String? = null,
    val queriedAt: String? = null,
    val progresses: List<DeliveryTraceProgress> = emptyList()
)

@Serializable
data class DeliveryTraceProgress(
    val dateTime: String? = null,
    val location: String? = null,
    val status: String? = null,
    val statusCode: String? = null,
    val description: String? = null
)

@Serializable
data class DeliveryTraceError(
    val code: String? = null,
    val message: String? = null,
    val courierCode: String? = null,
    val trackingNumber: String? = null,
    val billable: Boolean? = null
)

@Serializable
data class DeliveryWebhookPayload(
    val event: String? = null,
    val requestId: String? = null,
    val timestamp: String? = null,
    val items: List<DeliveryTrackingItem> = emptyList()
)

/** 웹훅·구독 상세의 운송장별 추적 결과 */
@Serializable
data class DeliveryTrackingItem(
    val courierCode: String? = null,
    val trackingNumber: String? = null,
    val clientId: String? = null,
    val currentStatus: String? = null,
    val isDelivered: Boolean? = null
)

class DeliveryApiClient {
    private val logger = logger()
    private val jsonConfig = Json { ignoreUnknownKeys = true }

    private val baseUrl = DotenvConfig
        .getOrDefault("DELIVERY_API_BASE_URL", "https://api.deliveryapi.co.kr")
        .trimEnd('/')

    private val apiKey = DotenvConfig["DELIVERY_API_KEY"]
    private val secretKey = DotenvConfig["DELIVERY_API_SECRET_KEY"]
    private val webhookEndpointId = DotenvConfig["DELIVERY_WEBHOOK_ENDPOINT_ID"]
    private val webhookSecret = DotenvConfig["DELIVERY_WEBHOOK_SECRET"]

    /** 추적 구독 API 사용 가능 여부 */
    val isEnabled = !apiKey.isNullOrBlank() && !secretKey.isNullOrBlank()  // 키 미설정 시 웹훅 검증만 동작

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonConfig, contentType = ContentType.Any)  // Content-Type과 무관하게 JSON으로 읽음
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
        }
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $apiKey:$secretKey")
        }
    }

    init {
        if (isEnabled) {
            logger.info("DeliveryApiClient 초기화 완료 (baseUrl={})", baseUrl)
        } else {
            logger.warn("Delivery API key/secret이 없어 외부 배송 추적 동기화가 비활성화됩니다")
        }
    }

    fun toCourierCode(carrier: String): String? {
        return when (carrier.trim()) {
            Constants.Commerce.CARRIER_CJ -> "cj"
            Constants.Commerce.CARRIER_EPOST -> "post"
            Constants.Commerce.CARRIER_HANJIN -> "hanjin"
            Constants.Commerce.CARRIER_LOTTE -> "lotte"
            Constants.Commerce.CARRIER_LOGEN -> "logen"
            else -> null
        }
    }

    suspend fun traceSingle(
        courierCode: String,
        trackingNumber: String,
        clientId: String? = null
    ): DeliveryTraceResult? {
        if (!isEnabled) return null

        return try {
            val response = client.post("$baseUrl/v1/tracking/trace") {
                contentType(ContentType.Application.Json)
                setBody(
                    DeliveryTraceRequest(
                        items = listOf(
                            DeliveryTraceItemRequest(
                                courierCode = courierCode,
                                trackingNumber = trackingNumber,
                                clientId = clientId
                            )
                        )
                    )
                )
            }

            if (!response.status.isSuccess()) {
                logger.warn("Delivery API 요청 실패: status={}", response.status.value)
                return null
            }

            val parsed = response.body<DeliveryTraceResponse>()
            if (!parsed.isSuccess) {
                logger.warn("Delivery API 응답 실패: isSuccess=false")
                return null
            }

            parsed.data?.results?.firstOrNull()
        } catch (e: Exception) {
            logger.warn("Delivery API 호출 실패: {}", e.message)
            null
        }
    }

    suspend fun subscribeTracking(
        courierCode: String,
        trackingNumber: String,
        clientId: String
    ): String? {
        if (!isEnabled) return null

        val endpointId = webhookEndpointId
        if (endpointId.isNullOrBlank()) {
            logger.warn("DELIVERY_WEBHOOK_ENDPOINT_ID가 없어 배송 추적 구독을 건너뜁니다")
            return null
        }

        return try {
            val response = client.post("$baseUrl/v1/webhooks/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    DeliveryRegisterRequest(
                        items = listOf(DeliveryTraceItemRequest(courierCode, trackingNumber, clientId)),
                        recurring = true,  // 배송완료까지 14일간 1시간 주기 조회
                        endpointId = endpointId
                    )
                )
            }

            if (!response.status.isSuccess()) {
                // INVALID_ITEMS면 data에 항목별 사유
                logger.warn("배송 추적 구독 실패: status={}, body={}", response.status.value, response.bodyAsText().take(500))
                return null
            }

            val parsed = response.body<DeliveryRegisterResponse>()
            if (!parsed.isSuccess) {
                logger.warn("배송 추적 구독 응답 실패: isSuccess=false")
                return null
            }

            parsed.data?.requestId
        } catch (e: Exception) {
            logger.warn("배송 추적 구독 요청 실패: {}", e.message)
            null
        }
    }

    /** 웹훅 누락 보정용 운송장별 추적 결과 */
    suspend fun getTrackingItems(requestId: String): List<DeliveryTrackingItem>? {
        if (!isEnabled) return null

        return try {
            val response = client.get("$baseUrl/v1/webhooks/subscriptions/$requestId")

            if (!response.status.isSuccess()) {
                logger.warn("배송 추적 구독 조회 실패: requestId={}, status={}", requestId, response.status.value)
                return null
            }

            val parsed = response.body<DeliverySubscriptionDetailResponse>()
            if (!parsed.isSuccess) return null
            parsed.data?.items
        } catch (e: Exception) {
            logger.warn("배송 추적 구독 조회 요청 실패: requestId={}, error={}", requestId, e.message)
            null
        }
    }

    fun parseWebhookPayload(rawBody: String): DeliveryWebhookPayload? {
        return try {
            jsonConfig.decodeFromString<DeliveryWebhookPayload>(rawBody)
        } catch (e: Exception) {
            logger.warn("배송 webhook payload 파싱 실패: {}", e.message)
            null
        }
    }

    fun verifyWebhookSignature(
        rawBody: String,
        signatureHeader: String?,
        timestampHeader: String?
    ): Boolean {
        val secret = webhookSecret
        if (secret.isNullOrBlank()) {  // 미설정 시 전체 거부
            logger.warn("DELIVERY_WEBHOOK_SECRET이 없어 webhook 요청을 거부합니다")
            return false
        }

        val signature = signatureHeader?.removePrefix("sha256=")
        if (signature.isNullOrBlank()) return false

        val timestamp = timestampHeader?.toLongOrNull() ?: return false
        val nowEpochSeconds = System.currentTimeMillis() / 1000
        if (abs(nowEpochSeconds - timestamp) > 300) {  // 300초 벗어나면 리플레이 거부
            logger.warn("Delivery webhook timestamp가 허용 범위를 벗어났습니다")
            return false
        }

        val signedPayload = "$timestamp.$rawBody"
        val expectedSignature = hmacSha256Hex(secret, signedPayload)

        return MessageDigest.isEqual(  // 상수시간 비교
            expectedSignature.toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8)
        )
    }

    private fun hmacSha256Hex(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
