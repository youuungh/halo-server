package com.ninezero.core.database.entities.user

import com.ninezero.core.common.config.UserRole
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object UserTable : BaseIntIdTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255).nullable()
    val username = varchar("username", 50).uniqueIndex()
    val role = enumerationByName<UserRole>("role", 50).default(UserRole.USER)
    val passwordResetToken = varchar("password_reset_token", 255).nullable()
    val passwordResetTokenExpiry = datetime("password_reset_token_expiry").nullable()
    val passwordResetCode = varchar("password_reset_code", 255).nullable() // SHA-256 해시
    val passwordResetCodeExpiry = datetime("password_reset_code_expiry").nullable()
    val passwordResetCodeAttempts = integer("password_reset_code_attempts").default(0)
    val passwordResetCodeSentCount = integer("password_reset_code_sent_count").default(0)
    val passwordResetCodeLastSentAt = datetime("password_reset_code_last_sent_at").nullable()
    val isActive = bool("is_active").default(true)
}

class UserDao(id: EntityID<Int>) : BaseIntEntity(id, UserTable) {
    companion object : BaseIntEntityClass<UserDao>(UserTable)

    var email by UserTable.email
    var passwordHash by UserTable.passwordHash
    var username by UserTable.username
    var role by UserTable.role
    var passwordResetToken by UserTable.passwordResetToken
    var passwordResetTokenExpiry by UserTable.passwordResetTokenExpiry
    var passwordResetCode by UserTable.passwordResetCode
    var passwordResetCodeExpiry by UserTable.passwordResetCodeExpiry
    var passwordResetCodeAttempts by UserTable.passwordResetCodeAttempts
    var passwordResetCodeSentCount by UserTable.passwordResetCodeSentCount
    var passwordResetCodeLastSentAt by UserTable.passwordResetCodeLastSentAt
    var isActive by UserTable.isActive

    val profile by UserProfileDao optionalBackReferencedOn UserProfileTable.userId
}
