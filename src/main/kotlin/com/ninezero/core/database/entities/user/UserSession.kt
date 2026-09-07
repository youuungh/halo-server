package com.ninezero.core.database.entities.user

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object UserSessionTable : BaseIntIdTable("user_sessions") {
    val userId = integer("user_id").references(UserTable.id)
    val sessionId = varchar("session_id", 255).uniqueIndex()
    val userAgent = text("user_agent")
    val ipAddress = varchar("ip_address", 45)
    val deviceType = varchar("device_type", 50)
    val browser = varchar("browser", 100)
    val os = varchar("os", 100)
    val location = varchar("location", 100).nullable()
    val deviceId = varchar("device_id", 255).nullable().index()
    val lastActive = datetime("last_active")
}

class UserSessionDao(id: EntityID<Int>) : BaseIntEntity(id, UserSessionTable) {
    companion object : BaseIntEntityClass<UserSessionDao>(UserSessionTable)

    var userId by UserSessionTable.userId
    var sessionId by UserSessionTable.sessionId
    var userAgent by UserSessionTable.userAgent
    var ipAddress by UserSessionTable.ipAddress
    var deviceType by UserSessionTable.deviceType
    var browser by UserSessionTable.browser
    var os by UserSessionTable.os
    var location by UserSessionTable.location
    var deviceId by UserSessionTable.deviceId
    var lastActive by UserSessionTable.lastActive

    var user by UserDao referencedOn UserSessionTable.userId
}
