package com.ninezero.core.security

import at.favre.lib.crypto.bcrypt.BCrypt
import com.ninezero.core.common.config.Constants

object PasswordManager {
    
    fun hashPassword(password: String): String {
        return BCrypt.withDefaults().hashToString(Constants.BCRYPT_COST, password.toCharArray())
    }
    
    fun verifyPassword(password: String, hashedPassword: String): Boolean {
        return BCrypt.verifyer().verify(password.toCharArray(), hashedPassword).verified
    }
}
