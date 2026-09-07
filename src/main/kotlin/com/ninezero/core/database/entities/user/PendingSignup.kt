package com.ninezero.core.database.entities.user

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/** 인증 코드 대기 중인 가입 요청 */
object PendingSignupTable : BaseIntIdTable("pending_signups") {
    val email = varchar("email", 255).uniqueIndex()  // 코드 검증 전엔 미선점
    val username = varchar("username", 50)
    val passwordHash = varchar("password_hash", 255)
    val codeHash = varchar("code_hash", 255) // SHA-256 해시
    val codeExpiry = datetime("code_expiry")
    val attempts = integer("attempts").default(0)
    val sentCount = integer("sent_count").default(0)
    val lastSentAt = datetime("last_sent_at").nullable()
    val expiresAt = datetime("expires_at")  // 대기 만료, 코드 만료보다 긺
}

class PendingSignupDao(id: EntityID<Int>) : BaseIntEntity(id, PendingSignupTable) {
    companion object : BaseIntEntityClass<PendingSignupDao>(PendingSignupTable)

    var email by PendingSignupTable.email
    var username by PendingSignupTable.username
    var passwordHash by PendingSignupTable.passwordHash
    var codeHash by PendingSignupTable.codeHash
    var codeExpiry by PendingSignupTable.codeExpiry
    var attempts by PendingSignupTable.attempts
    var sentCount by PendingSignupTable.sentCount
    var lastSentAt by PendingSignupTable.lastSentAt
    var expiresAt by PendingSignupTable.expiresAt
}
