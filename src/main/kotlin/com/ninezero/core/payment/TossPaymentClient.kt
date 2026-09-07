package com.ninezero.core.payment

import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
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

/** 확정 거절 여부 */
val TossConfirmResult.Failure.isDefinitiveDecline: Boolean
    get() = httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429

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
private data class BillingByCardRequestBody(
    val customerKey: String,
    val cardNumber: String,
    val cardExpirationYear: String,
    val cardExpirationMonth: String,
    val customerIdentityNumber: String,
    val cardPassword: String? = null
)

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

    private val secretKey = DotenvConfig["TOSS_SECRET_KEY"]
    private val enabled = !secretKey.isNullOrBlank()

    // Basic base64
    private val authHeader: String? = secretKey?.let {
        "Basic " + Base64.getEncoder().encodeToString("$it:".toByteArray(Charsets.UTF_8))
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonConfig)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 70_000 // 빌링 청구는 최대 60초 소요
        }
    }

    init {
        if (enabled) {
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
        idempotencyKey: String? = null
    ): TossConfirmResult {
        if (!enabled || authHeader == null) {
            logger.error("토스 결제 승인 시도했으나 TOSS_SECRET_KEY 미설정")
            return TossConfirmResult.Failure(
                code = "TOSS_NOT_CONFIGURED",
                message = "결제 설정이 완료되지 않았습니다",
                httpStatus = 0
            )
        }

        return try {
            val response = client.post("$baseUrl/v1/payments/confirm") {
                header(HttpHeaders.Authorization, authHeader)
                idempotencyKey?.let { header("Idempotency-Key", it) }  // 중복 승인 방지
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
        idempotencyKey: String? = null
    ): TossCancelResult {
        if (!enabled || authHeader == null) {
            logger.error("토스 결제 취소 시도했으나 TOSS_SECRET_KEY 미설정")
            return TossCancelResult.Failure(
                code = "TOSS_NOT_CONFIGURED",
                message = "결제 설정이 완료되지 않았습니다",
                httpStatus = 0
            )
        }

        return try {
            val response = client.post("$baseUrl/v1/payments/$paymentKey/cancel") {
                header(HttpHeaders.Authorization, authHeader)
                idempotencyKey?.let { header("Idempotency-Key", it) }  // 중복 취소 방지
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
    suspend fun issueBillingKey(authKey: String, customerKey: String): TossBillingIssueResult =
        requestBillingIssue("$baseUrl/v1/billing/authorizations/issue") {
            setBody(BillingIssueRequestBody(authKey, customerKey))  // authKey → billingKey 교환
        }

    /** POST /v1/billing/authorizations/card 테스트 fallback */
    suspend fun issueBillingKeyByCard(
        customerKey: String,
        cardNumber: String,
        expirationYear: String,
        expirationMonth: String,
        identityNumber: String,
        cardPassword: String? = null
    ): TossBillingIssueResult =
        requestBillingIssue("$baseUrl/v1/billing/authorizations/card") {  // 카드정보 직접 전달
            setBody(
                BillingByCardRequestBody(
                    customerKey = customerKey,
                    cardNumber = cardNumber,
                    cardExpirationYear = expirationYear,
                    cardExpirationMonth = expirationMonth,
                    customerIdentityNumber = identityNumber,
                    cardPassword = cardPassword
                )
            )
        }

    private suspend fun requestBillingIssue(
        url: String,
        configureBody: HttpRequestBuilder.() -> Unit
    ): TossBillingIssueResult {
        if (!enabled || authHeader == null) {
            logger.error("빌링키 발급 시도했으나 TOSS_SECRET_KEY 미설정")
            return TossBillingIssueResult.Failure("TOSS_NOT_CONFIGURED", "결제 설정이 완료되지 않았습니다", 0)
        }
        return try {
            val response = client.post(url) {
                header(HttpHeaders.Authorization, authHeader)
                contentType(ContentType.Application.Json)
                configureBody()
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
        idempotencyKey: String? = null
    ): TossConfirmResult {
        if (!enabled || authHeader == null) {
            logger.error("빌링 청구 시도했으나 TOSS_SECRET_KEY 미설정")
            return TossConfirmResult.Failure("TOSS_NOT_CONFIGURED", "결제 설정이 완료되지 않았습니다", 0)
        }
        return try {
            val response = client.post("$baseUrl/v1/billing/$billingKey") {
                header(HttpHeaders.Authorization, authHeader)
                idempotencyKey?.let { header("Idempotency-Key", it) }  // 구독과 주기 조합 고유값
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
        check(enabled && authHeader != null) { "TOSS_SECRET_KEY 미설정으로 결제 조회 불가" }

        val response = client.get("$baseUrl/v1/payments/orders/$orderId") {
            header(HttpHeaders.Authorization, authHeader)
        }
        return when {
            response.status.isSuccess() ->
                jsonConfig.decodeFromString<TossPaymentResponse>(response.bodyAsText())
            response.status.value == 404 -> null // 결제 기록 없음 = 미결제
            else -> error("토스 결제 조회 실패: status=${response.status.value}")  // 예외라 정리 스케줄러가 미결제 오인취소 대신 스킵
        }
    }
}
