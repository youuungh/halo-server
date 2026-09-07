package com.ninezero.features.commerce.data

import com.ninezero.core.common.config.BillingKeyStatus
import com.ninezero.core.database.entities.commerce.BillingKeyDao
import com.ninezero.core.database.entities.commerce.BillingKeyTable
import org.jetbrains.exposed.sql.and

class BillingKeyRepositoryImpl : BillingKeyRepository {

    /** 유저의 ACTIVE 빌링키 조회 */
    override suspend fun findActiveByUserId(userId: Int): BillingKeyDao? =
        BillingKeyDao.find {
            (BillingKeyTable.userId eq userId) and (BillingKeyTable.status eq BillingKeyStatus.ACTIVE)
        }.firstOrNull()

    /** 유저의 ACTIVE 빌링키 전부 DELETED */
    override suspend fun deactivateAll(userId: Int) {
        BillingKeyDao.find {
            (BillingKeyTable.userId eq userId) and (BillingKeyTable.status eq BillingKeyStatus.ACTIVE)
        }.forEach { it.status = BillingKeyStatus.DELETED }  // 상태만 DELETED
    }

    /** 빌링키 생성 */
    override suspend fun create(
        userId: Int,
        customerKey: String,
        billingKey: String,
        cardCompany: String?,
        cardNumberMasked: String?,
        cardType: String?,
        ownerType: String?,
        authenticatedAt: String?
    ): BillingKeyDao = BillingKeyDao.new {
        this.userId = userId  // deactivateAll 선행 전제
        this.customerKey = customerKey
        this.billingKey = billingKey
        this.cardCompany = cardCompany
        this.cardNumberMasked = cardNumberMasked
        this.cardType = cardType
        this.ownerType = ownerType
        this.authenticatedAt = authenticatedAt
    }
}
