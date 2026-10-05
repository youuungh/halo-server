package com.ninezero.core.security

import java.security.MessageDigest
import java.util.HexFormat

object TokenHasher {
    private val HEX = HexFormat.of()

    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return HEX.formatHex(digest.digest(token.toByteArray(Charsets.UTF_8)))
    }
}
