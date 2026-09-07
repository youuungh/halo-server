package com.ninezero.features.commerce.data

import com.ninezero.core.database.entities.commerce.BillingKeyDao

interface BillingKeyRepository {
    // 빌링키 조회
    suspend fun findActiveByUserId(userId: Int): BillingKeyDao?

    // 빌링키 비활성화
    suspend fun deactivateAll(userId: Int)

    // 빌링키 생성
    suspend fun create(
        userId: Int,
        customerKey: String,
        billingKey: String,
        cardCompany: String?,
        cardNumberMasked: String?,
        cardType: String?,
        ownerType: String?,
        authenticatedAt: String?
    ): BillingKeyDao
}
