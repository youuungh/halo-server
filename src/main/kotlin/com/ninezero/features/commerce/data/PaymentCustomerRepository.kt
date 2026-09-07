package com.ninezero.features.commerce.data

import com.ninezero.core.database.entities.commerce.PaymentCustomerDao

interface PaymentCustomerRepository {
    // customerKey 조회
    suspend fun findByUserId(userId: Int): PaymentCustomerDao?

    // customerKey 생성
    suspend fun create(userId: Int, customerKey: String): PaymentCustomerDao
}
