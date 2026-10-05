package com.ninezero.plugins

import com.ninezero.core.security.JwtConfig
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun Application.configureSecurity() {
    authentication {
        // JWT 인증
        jwt("jwt") {
            realm = JwtConfig.REALM
            verifier(JwtConfig.verifier)
            validate { credential -> JWTPrincipal(credential.payload) }
        }
    }
}
