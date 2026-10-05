package com.ninezero.features.commerce.data

import com.ninezero.core.database.entities.commerce.PaymentCustomerDao
import com.ninezero.core.database.entities.commerce.PaymentCustomerTable

class PaymentCustomerRepositoryImpl : PaymentCustomerRepository {

    /** 유저의 customerKey 조회 */
    override suspend fun findByUserId(userId: Int): PaymentCustomerDao? =
        PaymentCustomerDao.find { PaymentCustomerTable.userId eq userId }.firstOrNull()

    /** customerKey 생성 */
    override suspend fun create(userId: Int, customerKey: String): PaymentCustomerDao =
        PaymentCustomerDao.new {
            this.userId = userId  // 유저당 1개, 중복 검사 없음
            this.customerKey = customerKey
        }
}
