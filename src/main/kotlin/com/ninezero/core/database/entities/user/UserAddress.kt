package com.ninezero.core.database.entities.user

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ReferenceOption

object UserAddressTable : BaseIntIdTable("user_addresses") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val recipientName = varchar("recipient_name", 100)
    val recipientPhone = varchar("recipient_phone", 20)
    val zipCode = varchar("zip_code", 10)
    val address = text("address")
    val addressDetail = text("address_detail").nullable()
    val memo = text("memo").nullable()
    val isDefault = bool("is_default").default(false)

    init {
        index(false, userId)
    }
}

class UserAddressDao(id: EntityID<Int>) : BaseIntEntity(id, UserAddressTable) {
    companion object : BaseIntEntityClass<UserAddressDao>(UserAddressTable)

    var userId by UserAddressTable.userId
    var recipientName by UserAddressTable.recipientName
    var recipientPhone by UserAddressTable.recipientPhone
    var zipCode by UserAddressTable.zipCode
    var address by UserAddressTable.address
    var addressDetail by UserAddressTable.addressDetail
    var memo by UserAddressTable.memo
    var isDefault by UserAddressTable.isDefault
}
