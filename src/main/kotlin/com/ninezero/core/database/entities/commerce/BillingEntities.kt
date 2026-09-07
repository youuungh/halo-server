package com.ninezero.core.database.entities.commerce

import com.ninezero.core.common.config.BillingKeyStatus
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

/** 토스 customerKey */
object PaymentCustomerTable : BaseIntIdTable("payment_customers") {
    val userId = integer("user_id").references(UserTable.id).uniqueIndex()
    val customerKey = varchar("customer_key", 300).uniqueIndex()  // 유저당 1개
}

class PaymentCustomerDao(id: EntityID<Int>) : BaseIntEntity(id, PaymentCustomerTable) {
    companion object : BaseIntEntityClass<PaymentCustomerDao>(PaymentCustomerTable)

    var userId by PaymentCustomerTable.userId
    var customerKey by PaymentCustomerTable.customerKey
}

/** 자동결제용 빌링키 */
object BillingKeyTable : BaseIntIdTable("billing_keys") {
    val userId = integer("user_id").references(UserTable.id)
    val customerKey = varchar("customer_key", 300)
    val billingKey = varchar("billing_key", 200)  // 재조회 불가, 즉시 저장
    val status = enumerationByName<BillingKeyStatus>("status", 20).default(BillingKeyStatus.ACTIVE)  // 유저당 ACTIVE 1개
    val cardCompany = varchar("card_company", 50).nullable()
    val cardNumberMasked = varchar("card_number_masked", 30).nullable()
    val cardType = varchar("card_type", 20).nullable()
    val ownerType = varchar("owner_type", 20).nullable()
    val authenticatedAt = varchar("authenticated_at", 50).nullable()
}

class BillingKeyDao(id: EntityID<Int>) : BaseIntEntity(id, BillingKeyTable) {
    companion object : BaseIntEntityClass<BillingKeyDao>(BillingKeyTable)

    var userId by BillingKeyTable.userId
    var customerKey by BillingKeyTable.customerKey
    var billingKey by BillingKeyTable.billingKey
    var status by BillingKeyTable.status
    var cardCompany by BillingKeyTable.cardCompany
    var cardNumberMasked by BillingKeyTable.cardNumberMasked
    var cardType by BillingKeyTable.cardType
    var ownerType by BillingKeyTable.ownerType
    var authenticatedAt by BillingKeyTable.authenticatedAt
}
