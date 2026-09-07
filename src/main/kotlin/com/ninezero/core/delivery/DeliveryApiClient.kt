package com.ninezero.core.delivery

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
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
    @SerialName("isSuccess") val isSuccess: Boolean,
    val data: DeliveryTraceData? = null
)

@Serializable
private data class DeliveryTraceData(
    val results: List<DeliveryTraceResult> = emptyList()
)

@Serializable
private data class DeliverySubscriptionRequest(
    val items: List<DeliverySubscriptionItemRequest>
)

@Serializable
private data class DeliverySubscriptionItemRequest(
    val id: String? = null,
    val courierCode: String,
    val trackingNumber: String,
    val endpointId: String,
    val subscribedStatuses: List<String>? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
private data class DeliverySubscriptionResponse(
    @SerialName("isSuccess") val isSuccess: Boolean,
    val data: DeliverySubscriptionData? = null
)

@Serializable
private data class DeliverySubscriptionData(
    val results: List<DeliverySubscriptionResult> = emptyList()
)

@Serializable
private data class DeliverySubscriptionResult(
    val success: Boolean = false,
    val subscriptionId: String? = null,
    val currentStatus: String? = null
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
    val subscriptionId: String? = null,
    val timestamp: String? = null,
    val data: DeliveryWebhookData? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class DeliveryWebhookData(
    val courierCode: String? = null,
    val trackingNumber: String? = null,
    val previousStatus: String? = null,
    val currentStatus: String? = null,
    val tracking: DeliveryWebhookTracking? = null
)

@Serializable
data class DeliveryWebhookTracking(
    val courier: String? = null,
    val courierName: String? = null,
    val trackingNumber: String? = null,
    val deliveryStatus: String? = null,
    val statusText: String? = null,
    val isDelivered: Boolean? = null,
    val currentLocation: String? = null,
    val progresses: List<DeliveryTraceProgress> = emptyList()
)

/** 배송추적 API 클라이언트 */
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
    private val enabled = !apiKey.isNullOrBlank() && !secretKey.isNullOrBlank()  // 키 미설정 시 웹훅 검증만 동작

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonConfig)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
        }
    }

    init {
        if (enabled) {
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
            else -> when (carrier.trim().lowercase()) {
                "cj", "post", "hanjin", "lotte", "logen" -> carrier.trim().lowercase()
                else -> null
            }
        }
    }

    suspend fun traceSingle(
        courierCode: String,
        trackingNumber: String,
        clientId: String? = null
    ): DeliveryTraceResult? {
        if (!enabled) return null

        return try {
            val response = client.post("$baseUrl/v1/tracking/trace") {
                header(HttpHeaders.Authorization, "Bearer $apiKey:$secretKey")
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

            val bodyText = response.bodyAsText()
            val parsed = jsonConfig.decodeFromString<DeliveryTraceResponse>(bodyText)
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
        id: String? = null,
        metadata: Map<String, String>? = null
    ): String? {
        if (!enabled) return null

        val endpointId = webhookEndpointId
        if (endpointId.isNullOrBlank()) {
            logger.warn("DELIVERY_WEBHOOK_ENDPOINT_ID가 없어 배송 추적 구독을 건너뜁니다")
            return null
        }

        return try {
            val response = client.post("$baseUrl/v1/tracking/subscriptions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey:$secretKey")
                contentType(ContentType.Application.Json)
                setBody(
                    DeliverySubscriptionRequest(
                        items = listOf(
                            DeliverySubscriptionItemRequest(
                                id = id,
                                courierCode = courierCode,
                                trackingNumber = trackingNumber,
                                endpointId = endpointId,
                                subscribedStatuses = listOf("IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"),  // 구독 대상 상태 3종만
                                metadata = metadata
                            )
                        )
                    )
                )
            }

            if (!response.status.isSuccess()) {
                logger.warn("배송 추적 구독 실패: status={}", response.status.value)
                return null
            }

            val bodyText = response.bodyAsText()
            val parsed = jsonConfig.decodeFromString<DeliverySubscriptionResponse>(bodyText)
            if (!parsed.isSuccess) {
                logger.warn("배송 추적 구독 응답 실패: isSuccess=false")
                return null
            }

            parsed.data?.results?.firstOrNull { it.success }?.subscriptionId
                ?: parsed.data?.results?.firstOrNull()?.subscriptionId
        } catch (e: Exception) {
            logger.warn("배송 추적 구독 요청 실패: {}", e.message)
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
