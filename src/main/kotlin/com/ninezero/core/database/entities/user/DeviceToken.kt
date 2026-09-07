package com.ninezero.core.database.entities.user

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID

object DeviceTokenTable : BaseIntIdTable("device_tokens") {
    val userId = integer("user_id").references(UserTable.id).index()
    val deviceId = varchar("device_id", 255).uniqueIndex()  // 앱 설치당 UUID
    val fid = varchar("fid", 255)  // FCM V1 FID
}

class DeviceTokenDao(id: EntityID<Int>) : BaseIntEntity(id, DeviceTokenTable) {
    companion object : BaseIntEntityClass<DeviceTokenDao>(DeviceTokenTable)

    var userId by DeviceTokenTable.userId
    var deviceId by DeviceTokenTable.deviceId
    var fid by DeviceTokenTable.fid
}
