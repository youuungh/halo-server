package com.ninezero.features.commerce.domain

import com.ninezero.core.common.exception.PaymentConfirmFailedException
import com.ninezero.core.common.util.query
import com.ninezero.core.payment.TossBillingIssueResult
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.features.commerce.data.BillingKeyRepository
import com.ninezero.features.commerce.data.PaymentCustomerRepository
import com.ninezero.features.commerce.presentation.models.response.BillingKeyResponse
import java.util.UUID

/** 빌링키 관리, 청구는 SubscriptionPaymentService */
class BillingService(
    private val paymentCustomerRepository: PaymentCustomerRepository,
    private val billingKeyRepository: BillingKeyRepository,
    private val tossPaymentClient: TossPaymentClient
) {
    /** WebView/SDK 주입용 customerKey */
    suspend fun getOrCreateCustomerKey(userId: Int): String = query {
        (paymentCustomerRepository.findByUserId(userId)  // 비밀키처럼 취급
            ?: paymentCustomerRepository.create(userId, UUID.randomUUID().toString()))
            .customerKey
    }

    suspend fun issueBillingKey(userId: Int, authKey: String): BillingKeyResponse {
        val customerKey = getOrCreateCustomerKey(userId)
        val result = tossPaymentClient.issueBillingKey(authKey, customerKey)
        return storeIssued(userId, customerKey, result)
    }

    /** 테스트용 카드 직접 입력 발급 */
    suspend fun issueBillingKeyByCard(
        userId: Int,
        cardNumber: String,
        expirationYear: String,
        expirationMonth: String,
        identityNumber: String,
        cardPassword: String?
    ): BillingKeyResponse {
        val customerKey = getOrCreateCustomerKey(userId)
        val result = tossPaymentClient.issueBillingKeyByCard(
            customerKey, cardNumber, expirationYear, expirationMonth, identityNumber, cardPassword
        )
        return storeIssued(userId, customerKey, result)
    }

    private suspend fun storeIssued(
        userId: Int,
        customerKey: String,
        result: TossBillingIssueResult
    ): BillingKeyResponse {
        val issued = when (result) {
            is TossBillingIssueResult.Failure ->
                throw PaymentConfirmFailedException(result.message ?: "빌링키 발급에 실패했어요")
            is TossBillingIssueResult.Success -> result.data
        }
        return query {
            // 유저당 활성 1개
            billingKeyRepository.deactivateAll(userId)
            val dao = billingKeyRepository.create(
                userId = userId,
                customerKey = customerKey,
                billingKey = issued.billingKey,
                cardCompany = issued.cardCompany ?: issued.card?.issuerCode,
                cardNumberMasked = issued.cardNumber ?: issued.card?.number,
                cardType = issued.card?.cardType,
                ownerType = issued.card?.ownerType,
                authenticatedAt = issued.authenticatedAt
            )
            dao.toResponse()
        }
    }

    suspend fun getMyBillingKey(userId: Int): BillingKeyResponse = query {
        billingKeyRepository.findActiveByUserId(userId)?.toResponse()
            ?: BillingKeyResponse(registered = false)
    }

    suspend fun deleteBillingKey(userId: Int) = query {
        billingKeyRepository.deactivateAll(userId)
    }

    private fun com.ninezero.core.database.entities.commerce.BillingKeyDao.toResponse() =
        BillingKeyResponse(
            registered = true,
            cardCompany = cardCompany,
            cardNumberMasked = cardNumberMasked,
            cardType = cardType
        )
}
