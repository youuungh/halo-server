package com.ninezero.core.payment

import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import java.util.Base64

@Serializable
private data class TossConfirmRequest(
    val paymentKey: String,
    val orderId: String,
    val amount: Long
)

@Serializable
private data class TossCancelRequest(
    val cancelReason: String,
    val cancelAmount: Long? = null
)

/** 결제 승인 응답 */
@Serializable
data class TossPaymentResponse(
    val paymentKey: String,
    val orderId: String,
    val status: String,  // DONE/CANCELED/WAITING_FOR_DEPOSIT 등
    val totalAmount: Long,
    val method: String? = null,
    val approvedAt: String? = null,
    val receipt: TossReceipt? = null
)

@Serializable
data class TossReceipt(
    val url: String? = null
)

@Serializable
private data class TossErrorResponse(
    val code: String? = null,
    val message: String? = null
)

sealed interface TossConfirmResult {
    data class Success(val payment: TossPaymentResponse) : TossConfirmResult
    data class Failure(
        val code: String?,
        val message: String?,
        val httpStatus: Int
    ) : TossConfirmResult
}

/** 토스 자동결제 승인 API의 카드 확정 거절 코드 */
private val DEFINITIVE_DECLINE_CODES = setOf(
    "INVALID_STOPPED_CARD",
    "INVALID_REJECT_CARD",
    "INVALID_CARD_EXPIRATION",
    "INVALID_CARD_NUMBER",
    "REJECT_CARD_PAYMENT",
    "REJECT_ACCOUNT_PAYMENT",
    "REJECT_CARD_COMPANY",
    "EXCEED_MAX_AUTH_COUNT"
)

/** 재시도하지 않는 확정 거절 여부 */
val TossConfirmResult.Failure.isDefinitiveDecline: Boolean
    get() = code in DEFINITIVE_DECLINE_CODES

sealed interface TossCancelResult {
    data class Success(val payment: TossPaymentResponse) : TossCancelResult
    data class Failure(
        val code: String?,
        val message: String?,
        val httpStatus: Int
    ) : TossCancelResult
}

// 빌링

@Serializable
private data class BillingIssueRequestBody(val authKey: String, val customerKey: String)

@Serializable
private data class BillingChargeRequestBody(
    val amount: Long,
    val customerKey: String,
    val orderId: String,
    val orderName: String
)

/** 빌링키 발급 응답 */
@Serializable
data class TossBillingIssueResponse(
    val billingKey: String,
    val customerKey: String? = null,
    val authenticatedAt: String? = null,
    val cardCompany: String? = null,
    val cardNumber: String? = null,
    val card: TossBillingCard? = null
)

@Serializable
data class TossBillingCard(
    val number: String? = null,
    val cardType: String? = null,
    val ownerType: String? = null,
    val issuerCode: String? = null
)

sealed interface TossBillingIssueResult {
    data class Success(val data: TossBillingIssueResponse) : TossBillingIssueResult
    data class Failure(
        val code: String?,
        val message: String?,
        val httpStatus: Int
    ) : TossBillingIssueResult
}

/** 토스 결제 API 클라이언트 */
class TossPaymentClient {
    private val logger = logger()
    private val jsonConfig = Json { ignoreUnknownKeys = true }

    private val baseUrl = DotenvConfig
        .getOrDefault("TOSS_API_BASE_URL", "https://api.tosspayments.com")
        .trimEnd('/')

    // Basic base64
    private val authHeader: String? = DotenvConfig["TOSS_SECRET_KEY"]?.takeIf { it.isNotBlank() }?.let {
        "Basic " + Base64.getEncoder().encodeToString("$it:".toByteArray(Charsets.UTF_8))
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonConfig)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 70_000  // 빌링 청구는 최대 60초 소요
        }
    }

    init {
        if (authHeader != null) {
            logger.info("TossPaymentClient 초기화 완료 (baseUrl={})", baseUrl)
        } else {
            logger.warn("TOSS_SECRET_KEY가 없어 토스 결제 승인이 비활성화됩니다")
        }
    }

    /** POST /v1/payments/confirm */
    suspend fun confirm(
        paymentKey: String,
        orderId: String,
        amount: Long,
        idempotencyKey: String
    ): TossConfirmResult {
        val auth = authHeader ?: run {
            logger.error("토스 결제 승인 시도했으나 TOSS_SECRET_KEY 미설정")
            return TossConfirmResult.Failure(
                code = "TOSS_NOT_CONFIGURED",
                message = "결제 설정이 완료되지 않았습니다",
                httpStatus = 0
            )
        }

        return try {
            val response = client.post("$baseUrl/v1/payments/confirm") {
                header(HttpHeaders.Authorization, auth)
                header("Idempotency-Key", idempotencyKey)  // 중복 승인 방지
                contentType(ContentType.Application.Json)
                setBody(TossConfirmRequest(paymentKey, orderId, amount))
            }

            val bodyText = response.bodyAsText()

            if (response.status.isSuccess()) {
                TossConfirmResult.Success(
                    jsonConfig.decodeFromString<TossPaymentResponse>(bodyText)
                )
            } else {
                val error = runCatching {
                    jsonConfig.decodeFromString<TossErrorResponse>(bodyText)
                }.getOrNull()
                logger.warn(
                    "토스 결제 승인 실패: status={}, code={}, message={}",
                    response.status.value, error?.code, error?.message
                )
                TossConfirmResult.Failure(
                    code = error?.code,
                    message = error?.message,
                    httpStatus = response.status.value
                )
            }
        } catch (e: Exception) {
            logger.error("토스 결제 승인 호출 중 예외: {}", e.message)
            TossConfirmResult.Failure(
                code = "TOSS_REQUEST_FAILED",
                message = e.message,
                httpStatus = 0
            )
        }
    }

    /** POST /v1/payments/{paymentKey}/cancel */
    suspend fun cancel(
        paymentKey: String,
        cancelReason: String,
        cancelAmount: Long? = null,
        idempotencyKey: String
    ): TossCancelResult {
        val auth = authHeader ?: run {
            logger.error("토스 결제 취소 시도했으나 TOSS_SECRET_KEY 미설정")
            return TossCancelResult.Failure(
                code = "TOSS_NOT_CONFIGURED",
                message = "결제 설정이 완료되지 않았습니다",
                httpStatus = 0
            )
        }

        return try {
            val response = client.post("$baseUrl/v1/payments/$paymentKey/cancel") {
                header(HttpHeaders.Authorization, auth)
                header("Idempotency-Key", idempotencyKey)  // 중복 취소 방지
                contentType(ContentType.Application.Json)
                setBody(TossCancelRequest(cancelReason, cancelAmount))  // cancelAmount null이면 전액 취소
            }

            val bodyText = response.bodyAsText()

            if (response.status.isSuccess()) {
                TossCancelResult.Success(
                    jsonConfig.decodeFromString<TossPaymentResponse>(bodyText)
                )
            } else {
                val error = runCatching {
                    jsonConfig.decodeFromString<TossErrorResponse>(bodyText)
                }.getOrNull()
                logger.warn(
                    "토스 결제 취소 실패: status={}, code={}, message={}",
                    response.status.value, error?.code, error?.message
                )
                TossCancelResult.Failure(
                    code = error?.code,
                    message = error?.message,
                    httpStatus = response.status.value
                )
            }
        } catch (e: Exception) {
            logger.error("토스 결제 취소 호출 중 예외: {}", e.message)
            TossCancelResult.Failure(
                code = "TOSS_REQUEST_FAILED",
                message = e.message,
                httpStatus = 0
            )
        }
    }

    /** POST /v1/billing/authorizations/issue */
    suspend fun issueBillingKey(authKey: String, customerKey: String): TossBillingIssueResult {
        val auth = authHeader ?: run {
            logger.error("빌링키 발급 시도했으나 TOSS_SECRET_KEY 미설정")
            return TossBillingIssueResult.Failure("TOSS_NOT_CONFIGURED", "결제 설정이 완료되지 않았습니다", 0)
        }
        return try {
            val response = client.post("$baseUrl/v1/billing/authorizations/issue") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody(BillingIssueRequestBody(authKey, customerKey))  // authKey → billingKey 교환
            }
            val bodyText = response.bodyAsText()
            if (response.status.isSuccess()) {
                TossBillingIssueResult.Success(jsonConfig.decodeFromString<TossBillingIssueResponse>(bodyText))
            } else {
                val error = runCatching { jsonConfig.decodeFromString<TossErrorResponse>(bodyText) }.getOrNull()
                logger.warn(
                    "빌링키 발급 실패: status={}, code={}, message={}",
                    response.status.value, error?.code, error?.message
                )
                TossBillingIssueResult.Failure(error?.code, error?.message, response.status.value)
            }
        } catch (e: Exception) {
            logger.error("빌링키 발급 호출 중 예외: {}", e.message)
            TossBillingIssueResult.Failure("TOSS_REQUEST_FAILED", e.message, 0)
        }
    }

    /** POST /v1/billing/{billingKey} */
    suspend fun chargeBilling(
        billingKey: String,
        customerKey: String,
        amount: Long,
        orderId: String,
        orderName: String,
        idempotencyKey: String
    ): TossConfirmResult {
        val auth = authHeader ?: run {
            logger.error("빌링 청구 시도했으나 TOSS_SECRET_KEY 미설정")
            return TossConfirmResult.Failure("TOSS_NOT_CONFIGURED", "결제 설정이 완료되지 않았습니다", 0)
        }
        return try {
            val response = client.post("$baseUrl/v1/billing/$billingKey") {
                header(HttpHeaders.Authorization, auth)
                header("Idempotency-Key", idempotencyKey)  // 구독과 주기 조합 고유값
                contentType(ContentType.Application.Json)
                setBody(BillingChargeRequestBody(amount, customerKey, orderId, orderName))
            }
            val bodyText = response.bodyAsText()
            if (response.status.isSuccess()) {
                TossConfirmResult.Success(jsonConfig.decodeFromString<TossPaymentResponse>(bodyText))
            } else {
                val error = runCatching { jsonConfig.decodeFromString<TossErrorResponse>(bodyText) }.getOrNull()
                logger.warn(
                    "빌링 청구 실패: status={}, code={}, message={}",
                    response.status.value, error?.code, error?.message
                )
                TossConfirmResult.Failure(error?.code, error?.message, response.status.value)
            }
        } catch (e: Exception) {
            logger.error("빌링 청구 호출 중 예외: {}", e.message)
            TossConfirmResult.Failure("TOSS_REQUEST_FAILED", e.message, 0)
        }
    }

    /** GET /v1/payments/orders/{orderId} */
    suspend fun findPaymentByOrderId(orderId: String): TossPaymentResponse? {
        val auth = checkNotNull(authHeader) { "TOSS_SECRET_KEY 미설정으로 결제 조회 불가" }

        val response = client.get("$baseUrl/v1/payments/orders/$orderId") {
            header(HttpHeaders.Authorization, auth)
        }
        return when {
            response.status.isSuccess() ->
                jsonConfig.decodeFromString<TossPaymentResponse>(response.bodyAsText())
            response.status.value == 404 -> null  // 결제 기록 없음 = 미결제
            else -> error("토스 결제 조회 실패: status=${response.status.value}")  // 예외라 정리 스케줄러가 미결제 오인취소 대신 스킵
        }
    }

    /** GET /v1/payments/{paymentKey} */
    suspend fun findPaymentByKey(paymentKey: String): TossPaymentResponse? {
        val auth = checkNotNull(authHeader) { "TOSS_SECRET_KEY 미설정으로 결제 조회 불가" }

        val response = client.get("$baseUrl/v1/payments/$paymentKey") {
            header(HttpHeaders.Authorization, auth)
        }
        return when {
            response.status.isSuccess() ->
                jsonConfig.decodeFromString<TossPaymentResponse>(response.bodyAsText())
            response.status.value == 404 -> null
            else -> error("토스 결제 조회 실패: status=${response.status.value}")
        }
    }
}
