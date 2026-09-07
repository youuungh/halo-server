package com.ninezero.core.database.entities.user

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object RefreshTokenTable : BaseIntIdTable("refresh_tokens") {
    val userId = integer("user_id").references(UserTable.id)
    val sessionId = varchar("session_id", 255).index()
    val token = varchar("token", 255).uniqueIndex()
    val expiresAt = datetime("expires_at")
    val isRevoked = bool("is_revoked").default(false)
    val rotatedAt = datetime("rotated_at").nullable()
}

class RefreshTokenDao(id: EntityID<Int>) : BaseIntEntity(id, RefreshTokenTable) {
    companion object : BaseIntEntityClass<RefreshTokenDao>(RefreshTokenTable)

    var userId by RefreshTokenTable.userId
    var sessionId by RefreshTokenTable.sessionId
    var token by RefreshTokenTable.token
    var expiresAt by RefreshTokenTable.expiresAt
    var isRevoked by RefreshTokenTable.isRevoked
    var rotatedAt by RefreshTokenTable.rotatedAt

    var user by UserDao referencedOn RefreshTokenTable.userId
}
