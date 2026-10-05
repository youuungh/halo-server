package com.ninezero.core.database.entities.commerce

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
