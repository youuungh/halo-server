package com.ninezero.core.database.entities.user

import com.ninezero.core.database.entities.base.BaseIntIdTable

object DeviceTokenTable : BaseIntIdTable("device_tokens") {
    val userId = integer("user_id").references(UserTable.id).index()
    val deviceId = varchar("device_id", 255).uniqueIndex()  // 앱 설치당 UUID
    val fid = varchar("fid", 255)  // FCM V1 FID
}
