package com.ninezero.features.commerce.domain

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.PaymentConfirmFailedException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import com.ninezero.core.payment.TossBillingIssueResult
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.features.commerce.data.BillingKeyRepository
import com.ninezero.features.commerce.data.PaymentCustomerRepository
import com.ninezero.features.commerce.presentation.models.response.BillingKeyResponse
import com.ninezero.features.commerce.toBillingKeyResponse
import java.util.UUID

/** 빌링키 관리, 청구는 SubscriptionPaymentService */
class BillingService(
    private val paymentCustomerRepository: PaymentCustomerRepository,
    private val billingKeyRepository: BillingKeyRepository,
    private val tossPaymentClient: TossPaymentClient
) {
    /** WebView/SDK 주입용 customerKey */
    suspend fun getOrCreateCustomerKey(userId: Int): String = query {
        lockUser(userId)  // 동시 첫 등록 직렬화
        (paymentCustomerRepository.findByUserId(userId)  // 비밀키처럼 취급
            ?: paymentCustomerRepository.create(userId, UUID.randomUUID().toString()))
            .customerKey
    }

    suspend fun issueBillingKey(userId: Int, authKey: String): BillingKeyResponse {
        val customerKey = getOrCreateCustomerKey(userId)
        val issued = when (val result = tossPaymentClient.issueBillingKey(authKey, customerKey)) {
            is TossBillingIssueResult.Failure ->
                throw PaymentConfirmFailedException(
                    result.message ?: Errors.Commerce.Payment.BILLING_KEY_ISSUE_FAILED
                )
            is TossBillingIssueResult.Success -> result.data
        }
        return query {
            // 유저당 활성 1개
            lockUser(userId)
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
            dao.toBillingKeyResponse()
        }
    }

    suspend fun getMyBillingKey(userId: Int): BillingKeyResponse = query {
        billingKeyRepository.findActiveByUserId(userId)?.toBillingKeyResponse()
            ?: BillingKeyResponse(registered = false)
    }

    suspend fun deleteBillingKey(userId: Int) = query {
        billingKeyRepository.deactivateAll(userId)
    }

    /** 같은 사용자의 결제수단 변경 직렬화 */
    private fun lockUser(userId: Int) {
        UserDao.find { UserTable.id eq userId }.forUpdate().singleOrNull()
    }
}
